// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// NetBeans adaptation:
//   - ReferencesSearch uses LocalSearchScope to avoid requiring the global file index.
//   - Generator.generateDeclaration() is not called; suspend-modifier detection is skipped.
//   - Experimental opt-in annotation propagation is simplified to a no-op (no FqNames dependency).
//   - validate() / validateTempResult() removed (conflict detection is IDE-UI only).
package org.jetbrains.kotlin.idea.k2.refactoring.introduce.extractionEngine

import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaNonPublicApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDataFlowExitPointSnapshot
import org.jetbrains.kotlin.analysis.api.impl.base.components.KaBaseIllegalPsiException
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.k2.refactoring.extractFunction.*
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.*
import org.jetbrains.kotlin.idea.references.ReadWriteAccessChecker
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

internal class ExtractionDataAnalyzer(private val extractionData: ExtractionData) :
    AbstractExtractionDataAnalyzer<KaType, MutableParameter>(extractionData) {

    override fun hasSyntaxErrors(): Boolean = false

    /**
     * Finds declarations defined inside the selection that are also used outside it.
     *
     * Uses [LocalSearchScope] restricted to the containing file to avoid requiring the global
     * project-level file index (which is not available in the standalone NetBeans K2 session).
     */
    override fun getLocalDeclarationsWithNonLocalUsages(): List<KtNamedDeclaration> {
        val fileScope = LocalSearchScope(extractionData.originalFile)
        val definedDeclarations = mutableListOf<KtNamedDeclaration>()
        extractionData.expressions.forEach { p ->
            p.accept(object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    super.visitNamedDeclaration(declaration)
                    ReferencesSearch.search(declaration, fileScope).asIterable().forEach { ref ->
                        if (extractionData.expressions.none { PsiTreeUtil.isAncestor(it, ref.element, false) }) {
                            definedDeclarations.add(declaration)
                            return
                        }
                    }
                }
            })
        }
        return definedDeclarations
    }

    /**
     * Finds local variables that are modified inside the selection and read afterwards.
     *
     * Uses [LocalSearchScope] restricted to the containing file to avoid requiring the global
     * project-level file index.
     */
    override fun getVarDescriptorsAccessedAfterwards(): Set<String> {
        val fileScope = LocalSearchScope(extractionData.originalFile)
        val accessedLocals = mutableSetOf<String>()
        val allProperties = mutableSetOf<KtProperty>()
        val collector = object : VariableCollector() {
            override fun registerModifiedVar(e: KtProperty) { allProperties.add(e) }
        }
        extractionData.expressions.forEach { it.accept(collector) }
        for (prop in allProperties) {
            val name = prop.name ?: continue
            val afterwardsRef = ReferencesSearch.search(prop, fileScope).asIterable()
                .firstOrNull { ref ->
                    extractionData.expressions.none { PsiTreeUtil.isAncestor(it, ref.element, false) }
                }
            if (afterwardsRef != null) accessedLocals.add(name)
        }
        return accessedLocals
    }

    override fun getModifiedVars(): Map<String, List<org.jetbrains.kotlin.psi.KtExpression>> {
        val map = HashMap<String, ArrayList<org.jetbrains.kotlin.psi.KtExpression>>()
        val collector = object : VariableCollector() {
            override fun acceptProperty(prop: KtProperty): Boolean = prop.hasInitializer()
            override fun registerModifiedVar(e: KtProperty) {
                e.name?.let { key -> map.getOrPut(key) { ArrayList() } }
            }
        }
        extractionData.expressions.forEach { it.accept(collector) }
        return map
    }

    private abstract inner class VariableCollector : KtTreeVisitorVoid() {
        private val accessChecker: ReadWriteAccessChecker =
            ReadWriteAccessChecker.getInstance(extractionData.project)

        open fun acceptProperty(prop: KtProperty): Boolean = true

        override fun visitDeclaration(declaration: KtDeclaration) {
            if (declaration is KtProperty && declaration.isLocal && acceptProperty(declaration)) {
                registerModifiedVar(declaration)
            }
            super.visitDeclaration(declaration)
        }

        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
            if (accessChecker.readWriteAccessWithFullExpression(expression, true).first.isWrite) {
                val target = expression.mainReference.resolve()
                if (target is KtProperty && target.isLocal) registerModifiedVar(target)
                super.visitSimpleNameExpression(expression)
            }
        }

        abstract fun registerModifiedVar(e: KtProperty)
    }

    @OptIn(KaNonPublicApi::class)
    override fun createOutputDescriptor(): OutputDescriptor<KaType> =
        analyze(extractionData.commonParent) {
            @OptIn(KaImplementationDetail::class)
            KaBaseIllegalPsiException.allowIllegalPsiAccess {
                val exitSnapshot: KaDataFlowExitPointSnapshot =
                    computeExitPointSnapshot(extractionData.expressions)

                val defaultExpressionInfo = exitSnapshot.defaultExpressionInfo
                val typeOfDefaultFlow = defaultExpressionInfo?.type?.takeIf {
                    !extractionData.options.inferUnitTypeForUnusedValues ||
                            defaultExpressionInfo.expression.isUsedAsExpression
                }

                val scope = extractionData.targetSibling
                OutputDescriptor(
                    defaultResultExpression = defaultExpressionInfo?.expression,
                    typeOfDefaultFlow = approximateWithResolvableType(typeOfDefaultFlow, scope)
                        ?: builtinTypes.unit,
                    implicitReturn = exitSnapshot.valuedReturnExpressions
                        .filter { it !is KtReturnExpression }.singleOrNull(),
                    lastExpressionHasNothingType = extractionData.expressions.lastOrNull()
                        ?.let { analyze(it) { it.expressionType?.isNothingType } } == true,
                    valuedReturnExpressions = exitSnapshot.valuedReturnExpressions
                        .filter { it is KtReturnExpression },
                    returnValueType = approximateWithResolvableType(
                        exitSnapshot.returnValueType, scope
                    ) ?: builtinTypes.unit,
                    jumpExpressions = exitSnapshot.jumpExpressions.filter {
                        it is KtBreakExpression ||
                                it is KtContinueExpression ||
                                it is KtReturnExpression && it.returnedExpression == null
                    },
                    hasSingleTarget = !exitSnapshot.hasMultipleJumpTargets,
                    sameExitForDefaultAndJump = if (exitSnapshot.hasJumps)
                        !exitSnapshot.hasEscapingJumps && defaultExpressionInfo != null
                    else
                        defaultExpressionInfo == null,
                )
            }
        }

    override val nameSuggester = KotlinNameSuggester

    override val typeDescriptor: TypeDescriptor<KaType> = KotlinTypeDescriptor(extractionData)

    override fun inferParametersInfo(
        virtualBlock: KtBlockExpression,
        modifiedVariables: Set<String>,
    ): ParametersInfo<KaType, MutableParameter> {
        analyze(extractionData.commonParent) {
            return extractionData.inferParametersInfo(virtualBlock, modifiedVariables, typeDescriptor)
        }
    }

    @OptIn(KaExperimentalApi::class)
    override fun createDescriptor(
        suggestedFunctionNames: List<String>,
        defaultVisibility: KtModifierKeywordToken?,
        parameters: List<MutableParameter>,
        receiverParameter: MutableParameter?,
        typeParameters: List<TypeParameter>,
        replacementMap: MultiMap<KtSimpleNameExpression, IReplacement<KaType>>,
        flow: ControlFlow<KaType>,
        returnType: KaType,
    ): IExtractableCodeDescriptor<KaType> {
        var descriptor = ExtractableCodeDescriptor(
            context = extractionData.commonParent,
            extractionData = extractionData,
            suggestedNames = suggestedFunctionNames,
            visibility = extractionData.getDefaultVisibility(),
            parameters = parameters,
            receiverParameter = receiverParameter,
            typeParameters = typeParameters,
            replacementMap = replacementMap,
            controlFlow = flow,
            returnType = returnType,
            modifiers = emptyList(),
            optInMarkers = emptyList(),       // experimental annotation propagation skipped
            renderedAnnotations = emptyList(), // experimental annotation propagation skipped
        )
        // ExtractFunctionDescriptorModifier EP is not registered in standalone NetBeans sessions
        // (no plugin XML) — skip gracefully; it has no implementations in our environment.
        try {
            for (analyser in ExtractFunctionDescriptorModifier.EP_NAME.extensionList) {
                descriptor = analyser.modifyDescriptor(descriptor)
            }
        } catch (_: IllegalArgumentException) { /* EP absent in standalone */ }
        return descriptor
    }
}
