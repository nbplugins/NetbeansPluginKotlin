/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaParameterSymbol
import org.jetbrains.kotlin.idea.base.psi.moveInsideParenthesesAndReplaceWith
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.base.psi.unifier.toRange
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeInfo
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageProcessor
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinMethodDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinParameterInfo
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinTypeInfo
import org.jetbrains.kotlin.idea.k2.refactoring.introduce.K2SemanticMatcher
import org.jetbrains.kotlin.idea.refactoring.changeSignature.KotlinValVar
import org.jetbrains.kotlin.idea.refactoring.introduce.getContainingLambdaOutsideParentheses
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceParameter.IntroduceParameterDescriptor
import org.jetbrains.kotlin.idea.refactoring.introduce.mustBeParenthesizedInInitializerPosition
import org.jetbrains.kotlin.idea.refactoring.introduce.removeTemplateEntryBracesIfPossible
import org.jetbrains.kotlin.idea.refactoring.introduce.replaceWith
import org.jetbrains.kotlin.idea.refactoring.introduce.substringContextOrThis
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.getValueParameterList
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.Variance

/**
 * Headless analysis + execution engine for the **Introduce Parameter** refactoring (E9.13).
 *
 * Ported from IDEA's `KotlinFirIntroduceParameterHandler` (`addParameter`/`performRefactoring`):
 * turns a selected expression inside a function/secondary-constructor body (or a class's
 * property-initializer/body, targeting its primary constructor) into a new parameter, replacing
 * the expression at every matching occurrence with a reference to the parameter, and updating every
 * existing call site to pass the original expression as an argument (or as a declared default value
 * when [KaIntroduceParameterRequest.useDefaultValue] is set).
 *
 * Drives the real ported [IntroduceParameterDescriptor] (its `valVar`/`parametersToRemove`/
 * `newArgumentValue` properties are used as-is, not reimplemented) together with E9.8's
 * [KotlinMethodDescriptor]/[KotlinChangeInfo]/[KotlinChangeSignatureUsageProcessor] — the same
 * ported infrastructure [KaChangeSignatureComputer] already drives standalone. Following the same
 * convention as every other E9.x refactoring, `KotlinFirIntroduceParameterHandler`'s own
 * `performRefactoring()` (which needs an `Editor`) is never invoked directly: [apply] replicates its
 * three-pass usage-processing order plus its `occurrenceReplacer` logic by hand.
 *
 * **Known simplification vs. real IDEA** (documented, not silent): a selection that resolves to a
 * local `KtProperty` (IDEA's "introduce parameter from a local variable" path, which deletes the
 * variable and rewires its references) is rejected as [Outcome.NotApplicable] — only a plain
 * expression selection is supported. Likewise, a lambda-argument occurrence is always replaced with
 * a positional argument (IDEA's `shouldLambdaParameterBeNamed`/named-argument heuristic is not
 * ported); this only affects the readability of the rewritten call, not its correctness. Context
 * parameters are not handled by [findInternalUsagesOfParametersAndReceiver] — this plugin caps the
 * Kotlin language version below the context-parameters feature (see `CLAUDE.md`'s Key Versions).
 *
 * @param ktFile      the file being refactored
 * @param startOffset start of the selection
 * @param endOffset   end of the selection (exclusive)
 * @param project     IntelliJ project (for analysis)
 */
class KaIntroduceParameterComputer(
    private val ktFile: KtFile,
    private val startOffset: Int,
    private val endOffset: Int,
    private val project: com.intellij.openapi.project.Project,
) {
    /** Result of the analysis step. */
    sealed class Outcome {
        /** The selection is not a plain expression inside a valid target declaration. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the dialog/apply step needs. */
        data class Ready(val result: KaIntroduceParameterResult) : Outcome()
    }

    /** Outcome of actually applying the introduce-parameter transformation. */
    sealed class ApplyOutcome {
        /** Conflicts were found; [messages] are human-readable descriptions. Nothing was mutated. */
        data class Conflicts(val messages: List<String>) : ApplyOutcome()

        /** The change completed (in-memory PSI only); [fileTexts] maps each touched file's path to its resulting text. */
        data class Success(val fileTexts: Map<String, String>) : ApplyOutcome()

        /** Applying the change failed with [error]. */
        data class Error(val error: Throwable) : ApplyOutcome()
    }

    /**
     * Runs the full analysis and returns an [Outcome].
     *
     * @param targetParentOffset when non-null, use the enclosing declaration whose start offset
     *   equals this value (the user's scope-combo choice) instead of the innermost valid one.
     */
    fun compute(targetParentOffset: Int? = null): Outcome = try {
        computeInternal(targetParentOffset)
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    private fun computeInternal(targetParentOffset: Int?): Outcome {
        val expression = findExpression() ?: return Outcome.NotApplicable
        val physical = expression.substringContextOrThis as? KtExpression ?: return Outcome.NotApplicable
        if (physical is KtProperty) return Outcome.NotApplicable

        val candidates = collectTargetCandidates(expression)
        val targetParent = if (targetParentOffset != null) {
            candidates.firstOrNull { it.startOffset == targetParentOffset }
        } else {
            candidates.firstOrNull()
        } ?: return Outcome.NotApplicable

        return analyze(ktFile) {
            val expressionType = physical.expressionType ?: return@analyze Outcome.NotApplicable
            if (expressionType.isUnitType || expressionType.isNothingType) return@analyze Outcome.NotApplicable

            val typeText = expressionType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT)
            val supertypeTexts = expressionType.allSupertypes(shouldApproximate = true)
                .map { it.render(KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT) }
                .toList()

            val existingNames = targetParent.getValueParameterList()?.parameters?.mapNotNull { it.name }?.toSet() ?: emptySet()
            val suggestedNames = suggestParameterNames(physical, existingNames)

            val occurrenceCount = K2SemanticMatcher.findMatches(patternElement = expression, scopeElement = targetParent).size

            Outcome.Ready(
                KaIntroduceParameterResult(
                    selectionRange = TextRange(expression.startOffset, expression.textRange.endOffset),
                    suggestedNames = suggestedNames,
                    typeText = typeText,
                    supertypeTexts = supertypeTexts,
                    targetParentOffset = targetParent.startOffset,
                    occurrenceCount = maxOf(occurrenceCount, 1),
                )
            )
        }
    }

    /** Every enclosing function/secondary-constructor/primary-constructor-owning-class scope, innermost first. */
    fun collectScopeCandidates(): List<ScopeCandidate> {
        val expression = findExpression() ?: return emptyList()
        return collectTargetCandidates(expression).map { ScopeCandidate(renderLabel(it), it.startOffset) }
    }

    /**
     * Applies [request] to a fresh re-resolution of the selection and target declaration (never
     * reuses anything cached from [compute], same convention as [KaChangeSignatureComputer.apply]).
     */
    fun apply(request: KaIntroduceParameterRequest): ApplyOutcome = try {
        applyInternal(request)
    } catch (e: Exception) {
        ApplyOutcome.Error(e)
    }

    private fun applyInternal(request: KaIntroduceParameterRequest): ApplyOutcome {
        val expression = findExpression() ?: return ApplyOutcome.Error(IllegalStateException("Selection is no longer a valid expression"))
        val candidates = collectTargetCandidates(expression)
        val targetParent = candidates.firstOrNull { it.startOffset == (request.targetParentOffset ?: candidates.firstOrNull()?.startOffset) }
            ?: return ApplyOutcome.Error(IllegalStateException("Caret is no longer inside a valid function, constructor, or class"))

        return analyze(ktFile) {
            val occurrencesToReplace = if (request.replaceAllOccurrences) {
                K2SemanticMatcher.findMatches(patternElement = expression, scopeElement = targetParent)
                    .mapNotNull { (it as? KtExpression)?.toRange() }
                    .ifEmpty { listOf(expression.toRange()) }
            } else {
                listOf(expression.toRange())
            }

            val parametersUsages = findInternalUsagesOfParametersAndReceiver(targetParent)
            val psiFactory = KtPsiFactory(project)

            val descriptor = IntroduceParameterDescriptor(
                originalRange = expression.toRange(),
                callable = targetParent,
                callableDescriptor = targetParent,
                newParameterName = request.chosenName,
                newParameterTypeText = request.chosenTypeText,
                argumentValue = expression,
                withDefaultValue = request.useDefaultValue,
                parametersUsages = parametersUsages,
                occurrencesToReplace = occurrencesToReplace,
                occurrenceReplacer = replacer@{ range ->
                    val expressionToReplace = range.elements.singleOrNull() as? KtExpression ?: return@replacer
                    val replacingExpression = psiFactory.createExpression(request.chosenName)
                    val lambdaArgument = expressionToReplace.getContainingLambdaOutsideParentheses()
                    val result = when {
                        lambdaArgument != null -> lambdaArgument.moveInsideParenthesesAndReplaceWith(replacingExpression, null)
                        else -> expressionToReplace.replaced(replacingExpression)
                    }
                    result.removeTemplateEntryBracesIfPossible()
                },
            )

            applyDescriptor(descriptor, request)
        }
    }

    private fun applyDescriptor(
        descriptor: IntroduceParameterDescriptor<KtNamedDeclaration>,
        request: KaIntroduceParameterRequest,
    ): ApplyOutcome {
        val targetParent = descriptor.callable
        val methodDescriptor = KotlinMethodDescriptor(targetParent)
        val changeInfo = KotlinChangeInfo(methodDescriptor)

        val defaultValue = descriptor.newArgumentValue
        val parameterInfo = KotlinParameterInfo(
            originalIndex = -1,
            originalType = KotlinTypeInfo(descriptor.newParameterTypeText, targetParent),
            name = descriptor.newParameterName,
            valOrVar = descriptor.valVar,
            defaultValueForCall = defaultValue,
            defaultValueAsDefaultParameter = request.useDefaultValue,
            defaultValue = if (request.useDefaultValue) defaultValue else null,
            context = targetParent,
        )
        changeInfo.addParameter(parameterInfo, -1)

        if (!request.useDefaultValue) {
            val existingParameters = targetParent.getValueParameterList()?.parameters ?: emptyList()
            descriptor.parametersToRemove.filterIsInstance<KtParameter>()
                .map { existingParameters.indexOf(it) }
                .filter { it >= 0 }
                .sortedDescending()
                .forEach { changeInfo.removeParameter(it) }
        }

        // Unlike Change Signature (which adds a parameter with no known value and must forward it
        // through every caller's own signature — see KaChangeSignatureComputer.withCallerPropagation),
        // Introduce Parameter always has a concrete value for the new parameter: descriptor.newArgumentValue
        // (the original expression) is used as defaultValueForCall, so KotlinFunctionCallUsage inserts
        // it directly at every call site. No caller-propagation pass is needed or correct here.
        val processor = KotlinChangeSignatureUsageProcessor()
        val usages = processor.findUsages(changeInfo)

        val conflicts = processor.findConflicts(changeInfo, Ref(usages))
        if (!conflicts.isEmpty) {
            return ApplyOutcome.Conflicts(conflicts.values().toList())
        }

        for (usage in usages) {
            processor.processUsage(changeInfo, usage, beforeMethodChange = true, usages)
        }
        processor.processPrimaryMethod(changeInfo)
        for (usage in usages) {
            processor.processUsage(changeInfo, usage, beforeMethodChange = false, usages)
        }

        // Replace occurrences inside the body only after the signature change has landed, same
        // order as the ported handler's performRefactoring (ChangeSignature first, then occurrences).
        for (range in descriptor.occurrencesToReplace) {
            runCatching { descriptor.occurrenceReplacer(descriptor, range) }
        }

        val touchedFiles = mutableMapOf<String, KtFile>()
        (changeInfo.method.containingFile as? KtFile)?.let { touchedFiles[it.pathOrName()] = it }
        for (usage in usages) {
            (usage.element?.containingFile as? KtFile)?.let { touchedFiles[it.pathOrName()] = it }
        }
        (targetParent.containingFile as? KtFile)?.let { touchedFiles[it.pathOrName()] = it }

        return ApplyOutcome.Success(touchedFiles.mapValues { (_, file) -> file.text })
    }

    /**
     * Finds, for each existing value parameter of [targetParent], every reference to it within
     * [targetParent]'s own body — used by [IntroduceParameterDescriptor.parametersToRemove] to
     * detect parameters that become dead once their only usages are replaced by the new parameter.
     *
     * Simplified port of the handler's `findInternalUsagesOfParametersAndReceiver`: only plain
     * parameter references are tracked (no receiver/context-parameter handling — see this class's
     * doc for why context parameters are out of scope).
     */
    context(_: KaSession)
    private fun findInternalUsagesOfParametersAndReceiver(
        targetParent: KtNamedDeclaration
    ): MultiMap<org.jetbrains.kotlin.psi.KtElement, org.jetbrains.kotlin.psi.KtElement> {
        val usages = MultiMap<org.jetbrains.kotlin.psi.KtElement, org.jetbrains.kotlin.psi.KtElement>()
        targetParent.acceptChildren(object : KtTreeVisitorVoid() {
            override fun visitKtElement(element: org.jetbrains.kotlin.psi.KtElement) {
                super.visitKtElement(element)
                val symbol = element.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol
                val parameter = (symbol?.symbol as? KaParameterSymbol)?.psi as? KtParameter
                if (parameter != null && !parameter.hasValOrVar() && parameter.ownerDeclaration == targetParent) {
                    usages.putValue(parameter, element)
                }
            }
        })
        return usages
    }

    /** Resolves the single expression at `[startOffset, endOffset)`, or `null` if none. */
    private fun findExpression(): KtExpression? {
        if (startOffset > endOffset) return null
        if (startOffset == endOffset) {
            // Pure caret placement, no selection: expand to the innermost enclosing expression,
            // same as IDEA's smart-caret behavior for Introduce Parameter/Variable. A caret on a
            // keyword or other non-expression token (e.g. `fun`) correctly yields null here since
            // none of its ancestors are a KtExpression.
            val leaf = ktFile.findElementAt(startOffset) ?: return null
            return leaf.parentsWithSelf.filterIsInstance<KtExpression>().firstOrNull()
        }
        val tight = PsiTreeUtil.findElementOfClassAtRange(ktFile, startOffset, endOffset, KtExpression::class.java)
        if (tight != null && tight.textRange.startOffset == startOffset && tight.textRange.endOffset == endOffset) return tight
        val startEl = ktFile.findElementAt(startOffset) ?: return null
        val endEl = ktFile.findElementAt(endOffset - 1) ?: return null
        val commonParent = PsiTreeUtil.findCommonParent(startEl, endEl) ?: return null
        return commonParent as? KtExpression
            ?: PsiTreeUtil.getParentOfType(commonParent, KtExpression::class.java, false)
    }

    /**
     * Walks up from [expression] collecting every valid target declaration (innermost first): a
     * [KtNamedFunction], a [KtSecondaryConstructor], or a [KtClass] that already has a primary
     * constructor (property-initializer/class-body selections target that constructor — a class
     * with *no* primary constructor yet is out of scope for this simplified port, unlike real
     * IDEA's "add one from scratch" path).
     */
    private fun collectTargetCandidates(expression: KtExpression): List<KtNamedDeclaration> {
        val result = mutableListOf<KtNamedDeclaration>()
        for (candidate in expression.parentsWithSelf) {
            val target: KtNamedDeclaration? = when {
                candidate is KtNamedFunction -> candidate
                candidate is KtSecondaryConstructor -> candidate
                candidate is KtClass && !candidate.isInterface() -> candidate.primaryConstructor
                else -> null
            }
            if (target != null) result += target
        }
        return result
    }

    private fun renderLabel(declaration: KtNamedDeclaration): String = when (declaration) {
        is KtNamedFunction -> "fun ${declaration.name ?: "<anonymous>"}"
        is KtSecondaryConstructor -> "constructor ${(declaration.getContainingClassOrObject()).name ?: ""}".trim()
        else -> declaration.name ?: (declaration.parent as? org.jetbrains.kotlin.psi.KtPrimaryConstructor)?.let { "" } ?: "<declaration>"
    }

    context(_: KaSession)
    private fun suggestParameterNames(expression: KtExpression, existingNames: Set<String>): List<String> {
        if (expression is org.jetbrains.kotlin.psi.KtReferenceExpression) {
            val text = expression.text.takeIf { it.isNotBlank() && it.firstOrNull()?.isLetter() == true }
            if (text != null && text !in existingNames) return listOf(text, "p0")
        }
        return listOf("p0")
    }

    private fun KtFile.pathOrName(): String = virtualFile?.path ?: name
}

/**
 * Plain-data snapshot of the analysis, safe to reference from `Nbm` (unlike [IntroduceParameterDescriptor]/
 * [KotlinChangeInfo], which implement Java interfaces with no compiled `.class` in this module's jar
 * — see [KaChangeSignatureComputer]'s class doc for why).
 */
data class KaIntroduceParameterResult(
    val selectionRange: TextRange,
    val suggestedNames: List<String>,
    val typeText: String,
    val supertypeTexts: List<String>,
    val targetParentOffset: Int,
    val occurrenceCount: Int,
)

/** The caller's requested edits, passed to [KaIntroduceParameterComputer.apply]. */
data class KaIntroduceParameterRequest(
    val chosenName: String,
    val chosenTypeText: String,
    val replaceAllOccurrences: Boolean,
    val useDefaultValue: Boolean,
    val targetParentOffset: Int? = null,
)
