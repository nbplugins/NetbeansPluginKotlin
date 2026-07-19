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
@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt::class,
    org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction::class,
)

package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaParameterSymbol
import org.jetbrains.kotlin.idea.base.psi.isMultiLine
import org.jetbrains.kotlin.idea.base.psi.unifier.toRange
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeInfo
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageProcessor
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinMethodDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinParameterInfo
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinTypeInfo
import org.jetbrains.kotlin.idea.k2.refactoring.extractFunction.ExtractableCodeDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.extractFunction.ExtractionData
import org.jetbrains.kotlin.idea.k2.refactoring.extractFunction.ExtractionGeneratorConfiguration
import org.jetbrains.kotlin.idea.k2.refactoring.introduce.extractionEngine.ExtractionDataAnalyzer
import org.jetbrains.kotlin.idea.k2.refactoring.introduce.extractionEngine.Generator
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.AnalysisResult
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.ExtractionGeneratorOptions
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.ExtractionTarget
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceParameter.IntroduceParameterDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.getValueParameterList
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.Variance

/**
 * Headless analysis + execution engine for the **Introduce Functional Parameter** refactoring
 * (E9.14, Ctrl+Alt+Shift+P).
 *
 * Ported from IDEA's `KotlinFirIntroduceLambdaParameterHandler` and the
 * `lambdaExtractionDescriptor` branch of `KotlinIntroduceParameterDialog.performRefactoring()`:
 * wraps a selected expression's captured-variable signature in a functional type (`() -> Type` /
 * `(CapturedType) -> Type`), turns it into a new parameter (reusing the exact same
 * [IntroduceParameterDescriptor]/[KotlinChangeInfo]/[KotlinChangeSignatureUsageProcessor] pipeline
 * [KaIntroduceParameterComputer] drives for the plain Introduce Parameter refactoring), and
 * replaces the body occurrence(s) with a call to the new parameter while every existing call site
 * receives a lambda literal built from the original selection.
 *
 * Unlike [KaIntroduceParameterComputer]'s hand-written occurrence replacement, this class drives
 * the real **Extract Function engine** — [ExtractionDataAnalyzer] / [Generator.generateDeclaration]
 * with [ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION] — the same mechanism real IDEA uses to compute
 * which local variables/receiver the selection captures (these become the lambda's parameters) and
 * to replace the primary occurrence with a call, exactly mirroring
 * `KotlinFirIntroduceLambdaParameterHandler.calculateFunctionalType`/`createLambdaForArgument`.
 * [ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION] never inserts the synthetic function into the file
 * (`shouldInsert = false` in `ExtractFunctionGenerator`) — it exists only long enough to read its
 * signature off, matching real IDEA.
 *
 * **Known simplifications vs. real IDEA** (documented, not silent): only a single-expression
 * selection is supported (real IDEA's Extract-Function-engine-backed dialog also accepts a
 * multi-statement block selection — this port always resolves the selection via [findExpression],
 * the same caret/range logic [KaIntroduceParameterComputer] uses); selecting an *existing* lambda
 * argument is not specially handled (real IDEA wraps it in a zero-arg outer lambda invoked
 * immediately at the call site — the `lambdaArgument.kt` IDEA test case — this port has no
 * equivalent and such a selection is likely rejected or mishandled); context parameters are not
 * supported (same language-version cap as [KaIntroduceParameterComputer]). The final inserted
 * parameter's type text may appear fully qualified (e.g. `(kotlin.Int) -> kotlin.Int` instead of
 * `(Int) -> Int`) even though [renderFunctionalTypeFromDescriptor] renders it with short names —
 * `KotlinChangeSignatureUsageProcessor` (E9.8, shared by every E9.x refactoring that adds a
 * parameter) re-derives the final signature type from a parsed `KtTypeReference` further down its
 * own pipeline rather than preserving the short-name text verbatim; this is a pre-existing
 * characteristic of that shared infrastructure, not something new to this class, and out of scope
 * to fix here. The resulting code is still correct, valid Kotlin.
 *
 * @param ktFile      the file being refactored
 * @param startOffset start of the selection
 * @param endOffset   end of the selection (exclusive)
 * @param project     IntelliJ project (for analysis)
 */
class KaIntroduceFunctionalParameterComputer(
    private val ktFile: KtFile,
    private val startOffset: Int,
    private val endOffset: Int,
    private val project: Project,
) {
    /** Result of the analysis step. */
    sealed class Outcome {
        /** The selection is not a plain expression inside a valid target declaration. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the dialog/apply step needs. */
        data class Ready(val result: KaIntroduceFunctionalParameterResult) : Outcome()
    }

    /** Outcome of actually applying the introduce-functional-parameter transformation. */
    sealed class ApplyOutcome {
        /** Conflicts were found; [messages] are human-readable descriptions. Nothing was mutated. */
        data class Conflicts(val messages: List<String>) : ApplyOutcome()

        /** The change completed (in-memory PSI only); [fileTexts] maps each touched file's path to its resulting text. */
        data class Success(val fileTexts: Map<String, String>) : ApplyOutcome()

        /** Applying the change failed with [error]. */
        data class Error(val error: Throwable) : ApplyOutcome()
    }

    /**
     * Runs the full analysis and returns an [Outcome]. Read-only: never mutates [ktFile].
     *
     * @param targetParentOffset when non-null, use the enclosing declaration whose start offset
     *   equals this value (the user's scope-combo choice) instead of the innermost valid one —
     *   this is the declaration that receives the new parameter, independent of the (always
     *   innermost) scope used for the functional-type/captured-variable analysis below.
     */
    fun compute(targetParentOffset: Int? = null): Outcome = try {
        computeInternal(targetParentOffset)
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    private fun computeInternal(targetParentOffset: Int?): Outcome {
        val expression = findExpression() ?: return Outcome.NotApplicable

        val candidates = collectTargetCandidates(expression)
        val targetParent = if (targetParentOffset != null) {
            candidates.firstOrNull { it.startOffset == targetParentOffset }
        } else {
            candidates.firstOrNull()
        } ?: return Outcome.NotApplicable

        val descriptor = analyzeExtractable(expression, targetParent) ?: return Outcome.NotApplicable
        if (!ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION.isAvailable(descriptor)) return Outcome.NotApplicable

        val typeText = analyze(ktFile) { renderFunctionalTypeFromDescriptor(descriptor) }
        val existingNames = targetParent.getValueParameterList()?.parameters?.mapNotNull { it.name }?.toSet() ?: emptySet()
        val suggestedNames = listOf("function", "block").filter { it !in existingNames }.ifEmpty { listOf("p0") }

        return Outcome.Ready(
            KaIntroduceFunctionalParameterResult(
                selectionRange = TextRange(expression.startOffset, expression.textRange.endOffset),
                suggestedNames = suggestedNames,
                typeText = typeText,
                targetParentOffset = targetParent.startOffset,
                occurrenceCount = maxOf(descriptor.duplicates.size + 1, 1),
            )
        )
    }

    /** Every enclosing function/secondary-constructor/primary-constructor-owning-class scope, innermost first. */
    fun collectScopeCandidates(): List<ScopeCandidate> {
        val expression = findExpression() ?: return emptyList()
        return collectTargetCandidates(expression).map { ScopeCandidate(renderLabel(it), it.startOffset) }
    }

    /**
     * Applies [request] to a fresh re-resolution of the selection and target declaration (never
     * reuses anything cached from [compute], same convention as [KaIntroduceParameterComputer.apply]).
     */
    fun apply(request: KaIntroduceFunctionalParameterRequest): ApplyOutcome = try {
        applyInternal(request)
    } catch (e: Exception) {
        ApplyOutcome.Error(e)
    }

    private fun applyInternal(request: KaIntroduceFunctionalParameterRequest): ApplyOutcome {
        val expression = findExpression() ?: return ApplyOutcome.Error(IllegalStateException("Selection is no longer a valid expression"))
        val candidates = collectTargetCandidates(expression)
        val targetParent = candidates.firstOrNull { it.startOffset == (request.targetParentOffset ?: candidates.firstOrNull()?.startOffset) }
            ?: return ApplyOutcome.Error(IllegalStateException("Caret is no longer inside a valid function, constructor, or class"))

        val descriptor = analyzeExtractable(expression, targetParent)
            ?: return ApplyOutcome.Error(IllegalStateException("Selection is no longer extractable"))

        val finalName = request.chosenName.ifBlank { "function" }
        val namedDescriptor = descriptor.copy(suggestedNames = listOf(finalName), typeParameters = emptyList())
        val config = ExtractionGeneratorConfiguration(
            descriptor = namedDescriptor,
            generatorOptions = ExtractionGeneratorOptions.DEFAULT.copy(
                target = ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION,
                allowExpressionBody = false,
            ),
        )

        // Rendered from [descriptor] (short type names, via the same renderer [compute] uses for
        // its preview) *before* Generator.generateDeclaration mutates ktFile below — the generated
        // synthetic function's own PSI type-reference text would otherwise be fully-qualified,
        // since ExtractFunctionGenerator only runs ShortenReferencesFacility.shorten() when
        // shouldInsert is true, which FAKE_LAMBDALIKE_FUNCTION always sets to false (the synthetic
        // function is never inserted — see this class's own doc). Matches real IDEA's own
        // computation order: `calculateFunctionalType(oldDescriptor)` runs before the write action.
        val chosenTypeText = analyze(ktFile) { renderFunctionalTypeFromDescriptor(descriptor) }

        // Generator.generateDeclaration (target FAKE_LAMBDALIKE_FUNCTION) mutates ktFile in place:
        // it replaces the primary selected occurrence with a call to [finalName] and returns the
        // never-inserted synthetic KtFunction whose body drives the lambda-literal argument below —
        // exactly mirroring KotlinIntroduceParameterDialog.performRefactoring's lambda branch.
        val extractionResult = allowAnalysisOnEdt {
            allowAnalysisFromWriteAction {
                Generator.generateDeclaration(config, null)
            }
        }
        val declaration = extractionResult.declaration as? KtFunction
            ?: return ApplyOutcome.Error(IllegalStateException("Expected a function declaration from FAKE_LAMBDALIKE_FUNCTION extraction"))

        val argumentValue = createLambdaForArgument(declaration)

        // Real IDEA calls processDuplicates() here to show a confirm-per-duplicate UI backed by an
        // Editor; this headless port has no such UI, so every duplicate is replaced unconditionally
        // when the user checked "replace all occurrences" (same gating [KaIntroduceParameterComputer]
        // uses for its own K2SemanticMatcher-based occurrence list).
        if (request.replaceAllOccurrences) {
            for ((_, replacer) in extractionResult.duplicateReplacers) {
                runCatching { replacer() }
            }
        }

        val parametersUsages = analyze(ktFile) { findInternalUsagesOfParametersAndReceiver(targetParent) }

        val introduceDescriptor = IntroduceParameterDescriptor(
            originalRange = expression.toRange(),
            callable = targetParent,
            callableDescriptor = targetParent,
            newParameterName = finalName,
            newParameterTypeText = chosenTypeText,
            argumentValue = argumentValue,
            withDefaultValue = request.useDefaultValue,
            parametersUsages = parametersUsages,
            // The primary occurrence (and, when requested, every duplicate) was already replaced
            // above by Generator.generateDeclaration / the duplicate replacers — occurrencesToReplace
            // is empty and occurrenceReplacer is a no-op, matching real IDEA's `newReplacer = {}`.
            occurrencesToReplace = emptyList(),
            occurrenceReplacer = { _ -> },
        )

        return applyDescriptor(introduceDescriptor, request)
    }

    private fun applyDescriptor(
        descriptor: IntroduceParameterDescriptor<KtNamedDeclaration>,
        request: KaIntroduceFunctionalParameterRequest,
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

        for (range in descriptor.occurrencesToReplace) {
            runCatching { descriptor.occurrenceReplacer(descriptor, range) }
        }

        val touchedFiles = mutableMapOf<String, KtFile>()
        (changeInfo.method.containingFile as? KtFile)?.let { touchedFiles[it.pathOrName()] = it }
        for (usage in usages) {
            (usage.element?.containingFile as? KtFile)?.let { touchedFiles[it.pathOrName()] = it }
        }
        (targetParent.containingFile as? KtFile)?.let { touchedFiles[it.pathOrName()] = it }
        (ktFile).let { touchedFiles[it.pathOrName()] = it }

        return ApplyOutcome.Success(touchedFiles.mapValues { (_, file) -> file.text })
    }

    /**
     * Runs [ExtractionDataAnalyzer] over [expression], with [targetParent] itself (the declaration
     * chosen to receive the new parameter) as [ExtractionData.targetSibling] — ported verbatim from
     * `KotlinFirIntroduceLambdaParameterHandler.invoke()`'s `ExtractionData(targetParent
     * .containingKtFile, expression.toRange(), targetParent, duplicateContainer, ...)`. This is
     * deliberately *not* the innermost enclosing block ([KaExtractFunctionComputer]'s own
     * `findTargetSibling` convention): since the extracted code becomes a lambda literal living at
     * each *call site* — outside [targetParent] entirely — rather than a nested local function, any
     * variable [targetParent] itself declares (e.g. its own value parameters) must be captured as
     * an explicit lambda parameter, not left as a closure reference. Using [targetParent] as the
     * extraction scope boundary (instead of a block nested inside it) is what forces that captured
     * behavior. Returns `null` on critical analysis errors.
     */
    private fun analyzeExtractable(expression: KtExpression, targetParent: KtNamedDeclaration): ExtractableCodeDescriptor? {
        val duplicateContainer = when (targetParent) {
            is KtNamedFunction -> targetParent.bodyExpression
            is KtSecondaryConstructor -> targetParent.bodyExpression
            is org.jetbrains.kotlin.psi.KtPrimaryConstructor -> (targetParent.parent as? KtClass)?.body
            else -> null
        }
        val extractionData = ExtractionData(
            originalFile = ktFile,
            originalRange = expression.toRange(),
            targetSibling = targetParent,
            duplicateContainer = duplicateContainer,
        )
        val analysisResult = ExtractionDataAnalyzer(extractionData).performAnalysis()
        if (analysisResult.status == AnalysisResult.Status.CRITICAL_ERROR) return null
        return analysisResult.descriptor as? ExtractableCodeDescriptor
    }

    /**
     * Functional type text, rendered directly from [descriptor]'s captured parameters/receiver/
     * return type (no PSI mutation) — matches real IDEA's `calculateFunctionalType`. Used by both
     * [compute] (preview, before any function name has been chosen) and [apply] (called before
     * [Generator.generateDeclaration] mutates the file, so [descriptor]'s `KaType`s are still
     * valid) — deliberately *not* re-derived from the generated declaration's own PSI type-
     * reference text afterward, since `ExtractFunctionGenerator` only shortens references when
     * `shouldInsert` is true, which [ExtractionTarget.FAKE_LAMBDALIKE_FUNCTION] always sets to
     * false (the synthetic function is never inserted).
     */
    context(_: KaSession)
    private fun renderFunctionalTypeFromDescriptor(descriptor: ExtractableCodeDescriptor): String {
        val receiverText = descriptor.receiverParameter?.parameterType
            ?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT)
            ?.let { "$it." }
            ?: ""
        val paramsText = descriptor.parameters.joinToString(", ") {
            it.parameterType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT)
        }
        val returnText = if (descriptor.isUnitReturnType()) {
            "Unit"
        } else {
            descriptor.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT)
        }
        val suspendPrefix = if (KtTokens.SUSPEND_KEYWORD in descriptor.modifiers) "suspend " else ""
        return "$suspendPrefix$receiverText($paramsText) -> $returnText"
    }

    /**
     * Builds the lambda-literal argument text from the generated function's single statement —
     * ported verbatim from `KotlinIntroduceParameterDialog.createLambdaForArgument`.
     */
    private fun createLambdaForArgument(function: KtFunction): KtExpression {
        val statement = function.bodyBlockExpression!!.statements.single()
        val space = if (statement.isMultiLine()) "\n" else " "
        val parameters = function.valueParameters
        val parametersText = if (parameters.isNotEmpty()) {
            " " + parameters.joinToString { it.name ?: "_" } + " ->"
        } else ""
        val text = "{$parametersText$space${statement.text}$space}"
        return KtPsiFactory(project).createExpression(text)
    }

    /**
     * Finds, for each existing value parameter of [targetParent], every reference to it within
     * [targetParent]'s own body — used by [IntroduceParameterDescriptor.parametersToRemove] to
     * detect parameters that become dead once their only usages are replaced by the new parameter.
     * Identical simplified port to [KaIntroduceParameterComputer]'s own helper.
     */
    context(_: KaSession)
    private fun findInternalUsagesOfParametersAndReceiver(
        targetParent: KtNamedDeclaration
    ): MultiMap<KtElement, KtElement> {
        val usages = MultiMap<KtElement, KtElement>()
        targetParent.acceptChildren(object : KtTreeVisitorVoid() {
            override fun visitKtElement(element: KtElement) {
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

    /** Resolves the single expression at `[startOffset, endOffset)`, or `null` if none. Identical to [KaIntroduceParameterComputer.findExpression]. */
    private fun findExpression(): KtExpression? {
        if (startOffset > endOffset) return null
        if (startOffset == endOffset) {
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
     * Walks up from [expression] collecting every valid target declaration (innermost first).
     * Identical to [KaIntroduceParameterComputer.collectTargetCandidates].
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

    private fun KtFile.pathOrName(): String = virtualFile?.path ?: name
}

/**
 * Plain-data snapshot of the analysis, safe to reference from `Nbm`.
 */
data class KaIntroduceFunctionalParameterResult(
    val selectionRange: TextRange,
    val suggestedNames: List<String>,
    val typeText: String,
    val targetParentOffset: Int,
    val occurrenceCount: Int,
)

/** The caller's requested edits, passed to [KaIntroduceFunctionalParameterComputer.apply]. */
data class KaIntroduceFunctionalParameterRequest(
    val chosenName: String,
    val replaceAllOccurrences: Boolean,
    val useDefaultValue: Boolean,
    val targetParentOffset: Int? = null,
)
