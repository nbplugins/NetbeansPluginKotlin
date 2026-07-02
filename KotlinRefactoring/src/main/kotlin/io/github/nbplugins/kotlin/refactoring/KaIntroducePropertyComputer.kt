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
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.ExtractionOptions
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.Variance

/**
 * Headless analysis engine for the **Introduce Property** refactoring.
 *
 * Ported from IDEA's `KotlinIntroducePropertyHandler` (K2 variant).  Uses the same
 * [ExtractionDataAnalyzer] as [KaExtractFunctionComputer] but with
 * [ExtractionOptions.extractAsProperty] set to `true`, which directs the engine to produce a
 * property declaration rather than a function.
 *
 * Only class-body and file-level target siblings are accepted; function-block scopes are excluded
 * because a property cannot be declared inside a function body at the class-scope level.
 * Expressions that capture local variables (i.e. result descriptors with non-empty parameters)
 * are also rejected as [Outcome.NotApplicable].
 *
 * @param ktFile        the file being refactored
 * @param startOffset   start of the selection
 * @param endOffset     end of the selection (exclusive)
 * @param project       IntelliJ project (for analysis)
 */
class KaIntroducePropertyComputer(
    private val ktFile: KtFile,
    private val startOffset: Int,
    private val endOffset: Int,
    private val project: Project,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The selection is not on an extractable expression suitable for property introduction. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaIntroducePropertyResult) : Outcome()
    }

    /**
     * Runs the full analysis and returns an [Outcome].
     *
     * @param targetSiblingOffset when non-null, the property is inserted before the PSI element
     *   whose start offset equals this value; when null the innermost valid class-body or file
     *   scope is used.
     *
     * May be called off the EDT from NetBeans' refactoring thread.
     */
    fun compute(targetSiblingOffset: Int? = null): Outcome {
        val elements = findElements() ?: return Outcome.NotApplicable
        if (elements.isEmpty()) return Outcome.NotApplicable

        val targetSibling = if (targetSiblingOffset != null) {
            findElementAtStartOffset(targetSiblingOffset) ?: return Outcome.NotApplicable
        } else {
            findPropertyTargetSibling(elements.first()) ?: return Outcome.NotApplicable
        }

        // Verify the target sibling is in a class body or file (not a function block).
        val targetParent = targetSibling.parent
        if (targetParent !is KtClassBody && targetParent !is KtFile) return Outcome.NotApplicable

        return try {
            val extractionData = ExtractionData(
                originalFile = ktFile,
                originalRange = elements.toRange(false),
                targetSibling = targetSibling,
                options = ExtractionOptions(extractAsProperty = true),
            )
            val analysisResult = ExtractionDataAnalyzer(extractionData).performAnalysis()

            if (analysisResult.status == AnalysisResult.Status.CRITICAL_ERROR) {
                return Outcome.NotApplicable
            }

            val descriptor = analysisResult.descriptor as? ExtractableCodeDescriptor
                ?: return Outcome.NotApplicable

            // Reject expressions that capture local variables — they cannot become plain properties.
            if (descriptor.parameters.isNotEmpty()) return Outcome.NotApplicable

            analyze(ktFile) { buildResult(descriptor, elements, targetSibling) }
        } catch (e: Exception) {
            Outcome.Error(e)
        }
    }

    /**
     * Collects all valid target scopes reachable from the selection.
     *
     * Unlike [KaExtractFunctionComputer.collectScopeCandidates], only [KtClassBody] and [KtFile]
     * scopes are included — function blocks are excluded because properties must live at class or
     * file scope.
     *
     * @return ordered list of scope candidates (innermost first)
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
                container is KtClassBody -> result.add(
                    ScopeCandidate(renderClassBodyLabel(container), place.startOffset)
                )
                container is KtFile && !container.isScript() -> result.add(
                    ScopeCandidate(container.name, place.startOffset)
                )
                // Skip function blocks — properties cannot live there.
            }
        }
        return result
    }

    /**
     * Finds the PSI elements covered by [[startOffset]..[endOffset]).
     */
    private fun findElements(): List<PsiElement>? {
        if (startOffset >= endOffset) return null
        val startEl = ktFile.findElementAt(startOffset) ?: return null
        val endEl = ktFile.findElementAt(endOffset - 1) ?: return null
        val commonParent = PsiTreeUtil.findCommonParent(startEl, endEl) ?: return null

        val result = mutableListOf<PsiElement>()
        for (child in commonParent.children) {
            val childStart = child.textRange.startOffset
            val childEnd = child.textRange.endOffset
            if (childEnd > startOffset && childStart < endOffset) {
                result.add(child)
            }
        }
        if (result.isEmpty()) {
            if (commonParent.textRange.startOffset >= startOffset &&
                commonParent.textRange.endOffset <= endOffset
            ) {
                return listOf(commonParent)
            }
            val tight = PsiTreeUtil.findElementOfClassAtRange(
                ktFile, startOffset, endOffset, PsiElement::class.java
            )
            return if (tight != null) listOf(tight) else null
        }
        return result
    }

    /**
     * Walks up from [first] to find the nearest class-body or file-level target sibling
     * before which the new property will be inserted.
     */
    private fun findPropertyTargetSibling(first: PsiElement): PsiElement? {
        val parents = first.parentsWithSelf.toList()
        for (i in 0 until parents.size - 1) {
            val place = parents[i]
            val parent = parents[i + 1]
            when {
                parent is KtClassBody -> return place
                parent is KtFile -> return place
                // Continue past function bodies and other scopes.
            }
        }
        return null
    }

    /** Finds the PSI element whose [PsiElement.startOffset] equals [offset]. */
    private fun findElementAtStartOffset(offset: Int): PsiElement? {
        val leaf = ktFile.findElementAt(offset) ?: return null
        var el: PsiElement = leaf
        while (el.parent != null && el.parent.textRange.startOffset == offset) {
            el = el.parent
        }
        return el
    }

    /** Label for a [KtClassBody] scope: renders the enclosing class or object declaration. */
    private fun renderClassBodyLabel(classBody: KtClassBody): String = when (val cls = classBody.parent) {
        is KtClass -> "${cls.getDeclarationKeyword()?.text ?: "class"} ${cls.name ?: "<class>"}"
        is KtObjectDeclaration -> "object ${cls.name ?: "<object>"}"
        else -> "<class body>"
    }

    context(_: KaSession)
    private fun buildResult(
        descriptor: ExtractableCodeDescriptor,
        elements: List<PsiElement>,
        targetSibling: PsiElement,
    ): Outcome {
        val returnTypeText: String? = runCatching {
            descriptor.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT)
        }.getOrNull()

        val selectionStart = elements.first().startOffset
        val selectionEnd = elements.last().textRange.endOffset
        val selectionText = ktFile.text.substring(selectionStart, selectionEnd)

        val containerKind = when (targetSibling.parent) {
            is KtClassBody -> PropertyContainerKind.CLASS
            else -> PropertyContainerKind.FILE
        }

        return Outcome.Ready(
            KaIntroducePropertyResult(
                selectionRange = TextRange(selectionStart, selectionEnd),
                selectionText = selectionText,
                insertOffset = targetSibling.startOffset,
                suggestedNames = descriptor.suggestedNames.toList().ifEmpty { listOf("myProperty") },
                returnTypeText = returnTypeText,
                containerKind = containerKind,
            )
        )
    }
}

/** The container scope where the new property will be declared. */
enum class PropertyContainerKind {
    /** Property is inserted in a class body. */
    CLASS,
    /** Property is inserted at the top level of the file. */
    FILE,
}

/**
 * All data needed by the NetBeans apply element to perform the introduce-property transformation.
 *
 * @param selectionRange   range of the selected expression in the file (to be replaced by the property name)
 * @param selectionText    text of the selected expression (becomes the property initializer)
 * @param insertOffset     start offset of the target sibling before which the property is inserted
 * @param suggestedNames   candidate property names in preference order
 * @param returnTypeText   rendered type string, or `null` when the type is unavailable
 * @param containerKind    whether the property lives in a class body or at file level
 */
data class KaIntroducePropertyResult(
    val selectionRange: TextRange,
    val selectionText: String,
    val insertOffset: Int,
    val suggestedNames: List<String>,
    val returnTypeText: String?,
    val containerKind: PropertyContainerKind,
)
