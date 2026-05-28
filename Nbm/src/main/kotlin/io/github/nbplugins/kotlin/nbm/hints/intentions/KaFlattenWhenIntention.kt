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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtWhenExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Collapses a nested `when` in the `else` branch of another `when` into a single `when`.
 *
 * Applicable when:
 * - The outer `when` has exactly one `else` branch.
 * - The `else` body is a `when` expression with the same subject.
 * - The subjects are both simple name references (or both absent).
 *
 * For example:
 * ```kotlin
 * when (x) {
 *     1 -> "one"
 *     else -> when (x) {
 *         2 -> "two"
 *         else -> "other"
 *     }
 * }
 * ```
 * becomes:
 * ```kotlin
 * when (x) {
 *     1 -> "one"
 *     2 -> "two"
 *     else -> "other"
 * }
 * ```
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaFlattenWhenIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findWhenExpr(): KtWhenExpression? =
        PsiTreeUtil.getParentOfType(psi, KtWhenExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val whenExpr = findWhenExpr() ?: return false
        val subject = whenExpr.subjectExpression
        if (subject != null && subject !is KtNameReferenceExpression) return false
        if (!KtPsiUtil.checkWhenExpressionHasSingleElse(whenExpr)) return false

        val elseEntry = whenExpr.entries.singleOrNull { it.isElse } ?: return false
        val innerWhen = elseEntry.expression as? KtWhenExpression ?: return false
        return subjectsMatch(subject, innerWhen.subjectExpression)
    }

    override fun getDescription(): String = "Flatten 'when' expression"

    override fun implement() {
        val whenExpr = findWhenExpr() ?: return
        val elseEntry = whenExpr.entries.singleOrNull { it.isElse } ?: return
        val innerWhen = elseEntry.expression as? KtWhenExpression ?: return

        // Build flattened when text without using addRangeAfter (which requires treeCopyHandler).
        val subject = whenExpr.subjectExpression
        val header = if (subject != null) "when (${subject.text}) {\n" else "when {\n"
        val outerNonElse = whenExpr.entries.filter { !it.isElse }.joinToString("\n") { it.text }
        val innerEntries = innerWhen.entries.joinToString("\n") { it.text }
        val newText = "$header$outerNonElse\n$innerEntries\n}"

        val startOffset = whenExpr.textRange.startOffset
        val endOffset = whenExpr.textRange.endOffset
        doc.atomicChange {
            remove(startOffset, endOffset - startOffset)
            insertString(startOffset, newText, null)
        }
    }

    // Checks whether two when-subjects refer to the same name (both null or both same name).
    private fun subjectsMatch(outer: KtExpression?, inner: KtExpression?): Boolean {
        if (outer == null && inner == null) return true
        if (outer == null || inner == null) return false
        return outer.text == inner.text
    }
}
