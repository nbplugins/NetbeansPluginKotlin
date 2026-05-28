/*******************************************************************************
 * Copyright 2000-2022 JetBrains s.r.o.
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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Converts a `when` expression with no subject to an if/else-if chain.
 *
 * For example:
 * ```kotlin
 * when {
 *     a == 1 -> "one"
 *     a == 2 -> "two"
 *     else   -> "other"
 * }
 * ```
 * becomes:
 * ```kotlin
 * if (a == 1) "one"
 * else if (a == 2) "two"
 * else "other"
 * ```
 *
 * For a `when` with a subject, each condition is converted using the appropriate equality
 * or type-check expression.
 *
 * Applicable when the caret is on a `when` expression that has at least one entry and does not
 * have non-else branches after the first else branch.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaWhenToIfIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findWhenExpr(): KtWhenExpression? =
        PsiTreeUtil.getParentOfType(psi, KtWhenExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val whenExpr = findWhenExpr() ?: return false
        val entries = whenExpr.entries
        val lastEntry = entries.lastOrNull() ?: return false
        // Reject if any non-last entry is `else`
        if (entries.any { it != lastEntry && it.isElse }) return false
        // Reject single `else`-only when
        if (entries.size == 1 && lastEntry.isElse) return false
        // Reject subject that is a property (destructuring not supported)
        if (whenExpr.subjectExpression is KtProperty) return false
        return true
    }

    override fun getDescription(): String = "Replace 'when' with 'if'"

    override fun implement() {
        val whenExpr = findWhenExpr() ?: return
        val ifText = buildIfText(whenExpr) ?: return
        val startOffset = whenExpr.textRange.startOffset
        val endOffset = whenExpr.textRange.endOffset
        doc.atomicChange {
            remove(startOffset, endOffset - startOffset)
            insertString(startOffset, ifText, null)
        }
    }

    private fun buildIfText(whenExpr: KtWhenExpression): String? {
        val subject = whenExpr.subjectExpression
        val entries = whenExpr.entries
        val sb = StringBuilder()

        for ((i, entry) in entries.withIndex()) {
            if (i > 0) sb.append(" else ")
            val branch = entry.expression ?: return null

            if (entry.isElse) {
                sb.append(branch.text)
            } else {
                val condition = buildCondition(entry, subject) ?: return null
                if (branch is KtIfExpression) {
                    sb.append("if ($condition) { ${branch.text} }")
                } else {
                    sb.append("if ($condition) ${branch.text}")
                }
            }
        }
        return sb.toString()
    }

    private fun buildCondition(entry: KtWhenEntry, subject: KtExpression?): String? {
        val conditions = entry.conditions
        if (conditions.isEmpty()) return null

        val parts = conditions.mapNotNull { cond ->
            when (cond) {
                is KtWhenConditionWithExpression -> {
                    val expr = cond.expression ?: return@mapNotNull null
                    if (subject != null) {
                        "${subject.text} == ${expr.text}"
                    } else {
                        expr.text
                    }
                }
                is KtWhenConditionIsPattern -> {
                    val typeRef = cond.typeReference ?: return@mapNotNull null
                    val negation = if (cond.isNegated) "!is" else "is"
                    if (subject != null) {
                        "${subject.text} $negation ${typeRef.text}"
                    } else {
                        return@mapNotNull null
                    }
                }
                else -> null
            }
        }
        return if (parts.size == conditions.size) parts.joinToString(" || ") else null
    }
}
