/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)

package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.base.psi.unifier.toRange
import org.jetbrains.kotlin.idea.k2.refactoring.extractFunction.ExtractableCodeDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.extractFunction.ExtractionData
import org.jetbrains.kotlin.idea.k2.refactoring.introduce.extractionEngine.ExtractionDataAnalyzer
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.AnalysisResult
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.Variance

/**
 * Headless analysis engine for the **Extract Function** refactoring.
 *
 * Locates the selected [PsiElement]s within [ktFile] at [[startOffset]..[endOffset]], runs the
 * IDEA K2 [ExtractionDataAnalyzer] to determine parameters, return type, and name suggestions,
 * and returns all data the apply step needs to perform the text transformation.
 *
 * @param ktFile        the file being refactored
 * @param startOffset   start of the selection
 * @param endOffset     end of the selection (exclusive)
 * @param project       IntelliJ project (for analysis)
 */
class KaExtractFunctionComputer(
    private val ktFile: KtFile,
    private val startOffset: Int,
    private val endOffset: Int,
    private val project: Project,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The selection is not on extractable code. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaExtractFunctionResult) : Outcome()
    }

    /**
     * Runs the full analysis and returns an [Outcome].
     *
     * @param targetSiblingOffset when non-null, the extracted function is inserted before the
     *   PSI element whose start offset equals this value (scope selected by the user); when null
     *   the innermost valid container is used (default behaviour).
     *
     * May be called off the EDT from NetBeans' refactoring thread.
     */
    fun compute(targetSiblingOffset: Int? = null): Outcome {
        val elements = findElements() ?: return Outcome.NotApplicable
        if (elements.isEmpty()) return Outcome.NotApplicable
        val targetSibling = if (targetSiblingOffset != null) {
            findElementAtStartOffset(targetSiblingOffset) ?: return Outcome.NotApplicable
        } else {
            findTargetSibling(elements.first()) ?: return Outcome.NotApplicable
        }

        return try {
            val extractionData = ExtractionData(
                originalFile = ktFile,
                originalRange = elements.toRange(false),
                targetSibling = targetSibling,
            )
            val analysisResult = ExtractionDataAnalyzer(extractionData).performAnalysis()

            if (analysisResult.status == AnalysisResult.Status.CRITICAL_ERROR) {
                return Outcome.NotApplicable
            }

            val descriptor = analysisResult.descriptor as? ExtractableCodeDescriptor
                ?: return Outcome.NotApplicable

            analyze(ktFile) { buildResult(descriptor, elements) }
        } catch (e: Exception) {
            Outcome.Error(e)
        }
    }

    /**
     * Finds the PSI elements covered by [[startOffset]..[endOffset]).
     *
     * Collects all direct children of the common parent that fall within the range.
     */
    private fun findElements(): List<PsiElement>? {
        if (startOffset >= endOffset) return null
        val startEl = ktFile.findElementAt(startOffset) ?: return null
        val endEl = ktFile.findElementAt(endOffset - 1) ?: return null
        val commonParent = PsiTreeUtil.findCommonParent(startEl, endEl) ?: return null

        // If the selection covers commonParent's own range exactly (or within), that single node
        // *is* the selected element (e.g. a whole call expression like `println(a + b)`) — return
        // it as-is rather than decomposing into its direct children (callee + argument list),
        // which would silently drop non-KtExpression children (e.g. KtValueArgumentList) from
        // downstream analysis and lose references inside them (see E9 Phase 0 diagnostic).
        if (commonParent.textRange.startOffset >= startOffset &&
            commonParent.textRange.endOffset <= endOffset
        ) {
            return listOf(commonParent)
        }

        // Otherwise, collect children of commonParent that overlap the selection (multi-statement
        // selections spanning several siblings inside a block).
        val result = mutableListOf<PsiElement>()
        for (child in commonParent.children) {
            val childStart = child.textRange.startOffset
            val childEnd = child.textRange.endOffset
            if (childEnd > startOffset && childStart < endOffset) {
                result.add(child)
            }
        }
        if (result.isEmpty()) {
            // Try to find the tightest element that fits entirely within the range
            val tight = PsiTreeUtil.findElementOfClassAtRange(
                ktFile, startOffset, endOffset, PsiElement::class.java
            )
            return if (tight != null) listOf(tight) else null
        }
        return result
    }

    /**
     * Walks up from [first] to find the direct child of a containing block / file / class body
     * that will serve as the insertion anchor for the new function declaration.
     */
    private fun findTargetSibling(first: PsiElement): PsiElement? {
        val parents = first.parentsWithSelf.toList()
        for (i in 0 until parents.size - 1) {
            val place = parents[i]
            val parent = parents[i + 1]
            when {
                parent is KtBlockExpression -> return place
                parent is KtFile -> return place
                parent is KtClassBody -> return place
                parent is KtDeclarationWithBody && parent.bodyExpression == place -> return parent
            }
        }
        return null
    }

    /**
     * Collects all valid extraction scopes reachable from the selection as [ScopeCandidate]s.
     *
     * PSI-only — no K2 analysis required. Returns an empty list when [findElements] fails or the
     * selection has no valid parent containers. The first element is the innermost scope (same
     * scope as the default [compute] call); subsequent elements are progressively wider scopes.
     */
    fun collectScopeCandidates(): List<ScopeCandidate> {
        val elements = findElements() ?: return emptyList()
        val first = elements.firstOrNull() ?: return emptyList()
        val result = mutableListOf<ScopeCandidate>()
        val parents = first.parentsWithSelf.toList()
        for (i in 0 until parents.size - 1) {
            val place = parents[i]
            val container = parents[i + 1]
            when {
                container is KtBlockExpression -> result.add(
                    ScopeCandidate(renderBlockLabel(container), place.startOffset)
                )
                container is KtClassBody -> result.add(
                    ScopeCandidate(renderClassBodyLabel(container), place.startOffset)
                )
                container is KtFile && !container.isScript() -> result.add(
                    ScopeCandidate(container.name, place.startOffset)
                )
                // Expression body: the whole declaration becomes the targetSibling in the outer scope;
                // the outer scope level is picked up in the next iteration.
                container is KtDeclarationWithBody && container.bodyExpression == place -> continue
            }
        }
        return result
    }

    /**
     * Finds the PSI element whose [PsiElement.startOffset] equals [offset] — the same "place"
     * node [collectScopeCandidates] reported for that offset.
     *
     * Climbs from the leaf at [offset] while the parent's start offset still matches, but never
     * climbs into a scope-boundary container ([KtBlockExpression], [KtFile], [KtClassBody]):
     * those containers can start at the same offset as their first child (e.g. the first
     * top-level declaration in a file), which would otherwise make this return the container
     * itself instead of the declaration — an invalid extraction-insertion anchor.
     */
    private fun findElementAtStartOffset(offset: Int): PsiElement? {
        val leaf = ktFile.findElementAt(offset) ?: return null
        var el: PsiElement = leaf
        while (true) {
            val parent = el.parent ?: break
            if (parent is KtBlockExpression || parent is KtFile || parent is KtClassBody) break
            if (parent.textRange.startOffset != offset) break
            el = parent
        }
        return el
    }

    /** Label for a [KtBlockExpression] scope: renders the enclosing named function signature. */
    private fun renderBlockLabel(block: KtBlockExpression): String {
        val fn = block.parent as? KtNamedFunction ?: return "{...}"
        val params = fn.valueParameters.joinToString(", ") { p ->
            "${p.name ?: "_"}: ${p.typeReference?.text ?: "?"}"
        }
        val ret = fn.typeReference?.text?.let { ": $it" } ?: ""
        return "fun ${fn.name ?: "<anonymous>"}($params)$ret"
    }

    /** Label for a [KtClassBody] scope: renders the enclosing class or object declaration. */
    private fun renderClassBodyLabel(classBody: KtClassBody): String = when (val cls = classBody.parent) {
        is KtClass -> "${cls.getDeclarationKeyword()?.text ?: "class"} ${cls.name ?: "<class>"}"
        is KtObjectDeclaration -> "object ${cls.name ?: "<object>"}"
        else -> "<class body>"
    }

    context(_: KaSession)
    private fun buildResult(descriptor: ExtractableCodeDescriptor, elements: List<PsiElement>): Outcome {
        val params = descriptor.parameters.map { param ->
            ExtractedParameter(
                name = param.name,
                typeText = param.parameterType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT),
            )
        }

        val returnTypeText: String? = if (descriptor.isUnitReturnType()) {
            null
        } else {
            descriptor.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT)
        }

        val selectionStart = elements.first().startOffset
        val selectionEnd = elements.last().textRange.endOffset
        val selectionText = ktFile.text.substring(selectionStart, selectionEnd)

        return Outcome.Ready(
            KaExtractFunctionResult(
                selectionRange = TextRange(selectionStart, selectionEnd),
                selectionText = selectionText,
                insertOffset = descriptor.extractionData.targetSibling.startOffset,
                suggestedNames = descriptor.suggestedNames,
                parameters = params,
                returnTypeText = returnTypeText,
                isUnit = descriptor.isUnitReturnType(),
            )
        )
    }
}

/**
 * All data needed by the NetBeans apply element to perform the text transformation.
 *
 * @param selectionRange   range of the selected code in the file (to be replaced with call expression)
 * @param selectionText    text of the selected code (becomes the extracted function body)
 * @param insertOffset     start offset of the anchor element (where to insert the new function)
 * @param suggestedNames   candidate function names in preference order
 * @param parameters       parameters the extracted function should accept
 * @param returnTypeText   rendered return type string, or `null` for Unit
 * @param isUnit           true when the extracted expression/block returns Unit
 */
data class KaExtractFunctionResult(
    val selectionRange: TextRange,
    val selectionText: String,
    val insertOffset: Int,
    val suggestedNames: List<String>,
    val parameters: List<ExtractedParameter>,
    val returnTypeText: String?,
    val isUnit: Boolean,
)

/**
 * A single parameter of the extracted function.
 *
 * @param name     parameter name (from the analysis result)
 * @param typeText rendered type string (e.g. `"Int"`, `"List<String>"`)
 */
data class ExtractedParameter(
    val name: String,
    val typeText: String,
)

/**
 * One valid extraction scope returned by [KaExtractFunctionComputer.collectScopeCandidates].
 *
 * @param label               human-readable description shown in the dialog combo box
 *                            (e.g. `"fun myMethod(x: Int)"`, `"class Foo"`, `"MyFile.kt"`)
 * @param targetSiblingOffset start offset of the PSI element before which the new function will
 *                            be inserted when this scope is chosen; passed to
 *                            [KaExtractFunctionComputer.compute] as `targetSiblingOffset`
 */
data class ScopeCandidate(
    val label: String,
    val targetSiblingOffset: Int,
)
