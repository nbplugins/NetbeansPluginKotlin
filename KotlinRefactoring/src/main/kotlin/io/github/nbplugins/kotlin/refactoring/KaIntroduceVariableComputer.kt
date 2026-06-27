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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggester
import org.jetbrains.kotlin.idea.k2.refactoring.introduce.K2SemanticMatcher
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Headless analysis engine for the **Introduce Variable** refactoring.
 *
 * Locates the target [KtExpression] within [ktFile] at [[startOffset]..[endOffset]], computes all
 * semantically equivalent occurrences via [K2SemanticMatcher], suggests variable names using
 * [KotlinNameSuggester], and returns the anchor offset (where to insert the new declaration).
 *
 * @param ktFile        the file being refactored
 * @param startOffset   start of the selection (or caret offset when no selection)
 * @param endOffset     end of the selection (exclusive); equals [startOffset] for caret-only mode
 * @param project       IntelliJ project (for [analyze])
 */
class KaIntroduceVariableComputer(
    private val ktFile: KtFile,
    private val startOffset: Int,
    private val endOffset: Int,
    private val project: Project,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret/selection is not on a suitable expression. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaIntroduceVariableResult) : Outcome()
    }

    /**
     * Runs the full analysis and returns an [Outcome].
     *
     * This method may be called off the EDT from NetBeans' refactoring thread.
     */
    fun compute(): Outcome {
        val expression = findExpression() ?: return Outcome.NotApplicable
        return try {
            analyze(ktFile) { computeInternal(expression) }
        } catch (e: Exception) {
            Outcome.Error(e)
        }
    }

    /**
     * Finds the [KtExpression] covering [[startOffset]..[endOffset]).
     *
     * When [startOffset] == [endOffset] (caret-only), walks up to the nearest expression parent.
     */
    private fun findExpression(): KtExpression? {
        if (startOffset < endOffset) {
            val startEl = ktFile.findElementAt(startOffset) ?: return null
            val endEl = ktFile.findElementAt(endOffset - 1) ?: return null
            val common = PsiTreeUtil.findCommonParent(startEl, endEl) ?: return null
            return (common as? KtExpression)
                ?: PsiTreeUtil.getParentOfType(common, KtExpression::class.java, false)
        }
        val el = ktFile.findElementAt(startOffset) ?: return null
        return PsiTreeUtil.getParentOfType(el, KtExpression::class.java, false)
    }

    context(_: KaSession)
    private fun computeInternal(expression: KtExpression): Outcome {
        // Skip non-value expressions (declarations, statements without a type).
        if (expression is KtDeclaration) return Outcome.NotApplicable
        // Check that the expression has a type (filters out pure statements and declarations).
        expression.expressionType ?: return Outcome.NotApplicable

        val suggestedNames: List<String> = runCatching {
            KotlinNameSuggester().suggestExpressionNames(expression).take(5).toList()
        }.getOrElse { listOf("value") }.ifEmpty { listOf("value") }

        val typeText: String? = runCatching {
            expression.expressionType?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        }.getOrNull()

        val container = expression.findContainer() ?: return Outcome.NotApplicable

        val occurrences: List<KtExpression> = runCatching {
            K2SemanticMatcher.findMatches(expression, container).filterIsInstance<KtExpression>()
        }.getOrElse { listOf(expression) }.ifEmpty { listOf(expression) }

        val anchor = computeAnchor(occurrences, container) ?: return Outcome.NotApplicable

        return Outcome.Ready(
            KaIntroduceVariableResult(
                expressionRange = expression.textRange,
                expressionText = expression.text,
                suggestedNames = suggestedNames,
                occurrenceRanges = occurrences.map { it.textRange },
                anchorOffset = anchor.startOffset,
                typeText = typeText,
            )
        )
    }

    /**
     * Walks up from [this] expression to find the nearest enclosing block / body / file that can
     * serve as the declaration container.
     */
    private fun KtExpression.findContainer(): KtElement? {
        val selfAndParents = parentsWithSelf.toList()
        for (i in 0 until selfAndParents.size - 1) {
            val place = selfAndParents[i]
            val parent = selfAndParents[i + 1]
            when {
                parent is KtBlockExpression -> return parent
                parent is KtFile -> return parent
                parent is KtDeclarationWithBody && parent.bodyExpression == place -> return parent
                parent is KtClassBody -> return parent
            }
        }
        return null
    }

    /**
     * Returns the direct child of [container] that immediately precedes (or contains) the earliest
     * occurrence. The new declaration is inserted just before this element.
     *
     * Mirrors the logic of `calculateAnchorForExpressions` from IDEA's `introduceUtils.kt`.
     *
     * The common-parent approach was incorrect when occurrences span several direct children of
     * `container` (e.g. three `println(42)` statements): `findCommonParent` returns the container
     * itself, and walking up from the container can never satisfy `it.parent == container`.
     * The fix is to always start the walk from the **first** (textually earliest) occurrence.
     */
    private fun computeAnchor(occurrences: List<KtExpression>, container: KtElement): KtElement? {
        if (occurrences.isEmpty()) return null
        val first = occurrences.minByOrNull { it.startOffset } ?: return null
        return generateSequence(first as PsiElement) { it.parent }
            .firstOrNull { it.parent == container } as? KtElement
    }
}

/**
 * All data needed by the NetBeans apply element to perform the text transformation.
 *
 * @param expressionRange   range of the original expression in the file
 * @param expressionText    text of the expression (becomes the val initializer)
 * @param suggestedNames    candidate variable names in preference order
 * @param occurrenceRanges  text ranges of all semantically equivalent occurrences (unsorted)
 * @param anchorOffset      start offset of the anchor element (where to insert `val name = expr`)
 * @param typeText          rendered short-name type string for "specify type explicitly" option; null if unavailable
 */
data class KaIntroduceVariableResult(
    val expressionRange: TextRange,
    val expressionText: String,
    val suggestedNames: List<String>,
    val occurrenceRanges: List<TextRange>,
    val anchorOffset: Int,
    val typeText: String? = null,
)
