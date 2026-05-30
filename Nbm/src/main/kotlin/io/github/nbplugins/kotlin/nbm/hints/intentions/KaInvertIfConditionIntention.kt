/*******************************************************************************
 * Copyright 2000-2023 JetBrains s.r.o.
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
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Inverts the condition of an `if` expression and swaps the then/else branches.
 *
 * For an `if` with both branches:
 * ```kotlin
 * if (condition) thenBody else elseBody
 * ```
 * becomes:
 * ```kotlin
 * if (!condition) elseBody else thenBody
 * ```
 *
 * For an `if` with no `else` and an exit statement in the then-branch, moves the remaining
 * code under the inverted condition (early-exit pattern).
 *
 * Applicable when the caret is on the `if` keyword and the condition and then-branch are
 * non-null.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaInvertIfConditionIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findIfExpression(): KtIfExpression? =
        PsiTreeUtil.getParentOfType(psi, KtIfExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val ifExpr = findIfExpression() ?: return false
        return ifExpr.condition != null && ifExpr.then != null
    }

    override fun getDescription(): String = "Invert 'if' condition"

    override fun implement() {
        val ifExpr = findIfExpression() ?: return
        val condition = ifExpr.condition ?: return
        val thenBranch = ifExpr.then ?: return
        val elseBranch = ifExpr.`else`

        val newConditionText = negateCondition(condition)
        val startOffset = ifExpr.textRange.startOffset
        val endOffset = ifExpr.textRange.endOffset

        if (elseBranch != null) {
            // Standard inversion: swap then/else, negate condition
            val newText = "if ($newConditionText) ${elseBranch.text} else ${thenBranch.text}"
            doc.atomicChange {
                remove(startOffset, endOffset - startOffset)
                insertString(startOffset, newText, null)
            }
        } else if (thenBranch.lastBlockStatementOrThis().isExitStatement()) {
            // Exit-statement inversion in a block: move following statements under the inverted if
            val block = ifExpr.parent as? KtBlockExpression
            if (block != null) {
                val rBrace = block.rBrace
                val following = ifExpr.nextSiblings()
                    .takeWhile { it != rBrace }
                    .filterIsInstance<KtExpression>()
                    .toList()
                if (following.isNotEmpty()) {
                    val newBodyText = following.joinToString("\n") { it.text }
                    val blockStart = ifExpr.textRange.startOffset
                    val blockEnd = following.last().textRange.endOffset
                    val newIfText = "if ($newConditionText) {\n$newBodyText\n}"
                    doc.atomicChange {
                        remove(blockStart, blockEnd - blockStart)
                        insertString(blockStart, "$newIfText\n${thenBranch.text}", null)
                    }
                    return
                }
            }
            // Simple case: no following statements — just negate the condition
            val newText = "if ($newConditionText) ${thenBranch.text}"
            doc.atomicChange {
                remove(startOffset, endOffset - startOffset)
                insertString(startOffset, newText, null)
            }
        } else {
            val newText = "if ($newConditionText) ${thenBranch.text}"
            doc.atomicChange {
                remove(startOffset, endOffset - startOffset)
                insertString(startOffset, newText, null)
            }
        }
    }

    private fun negateCondition(condition: KtExpression): String {
        // Unwrap existing negation: `!expr` → `expr`
        if (condition is KtPrefixExpression &&
            condition.operationReference.getReferencedNameElementType() == KtTokens.EXCL) {
            val inner = condition.baseExpression
            if (inner is KtParenthesizedExpression) return inner.expression?.text ?: "!${condition.text}"
            return inner?.text ?: "!${condition.text}"
        }
        // Negate equality operators
        if (condition is KtBinaryExpression) {
            val op = condition.operationToken
            val negated = when (op) {
                KtTokens.EQEQ -> "!="
                KtTokens.EXCLEQ -> "=="
                KtTokens.LT -> ">="
                KtTokens.LTEQ -> ">"
                KtTokens.GT -> "<="
                KtTokens.GTEQ -> "<"
                else -> null
            }
            if (negated != null) {
                return "${condition.left?.text} $negated ${condition.right?.text}"
            }
        }
        return "!(${condition.text})"
    }

    private fun KtExpression.lastBlockStatementOrThis(): KtExpression =
        if (this is KtBlockExpression) statements.lastOrNull() ?: this else this

    private fun KtExpression.isExitStatement(): Boolean =
        this is org.jetbrains.kotlin.psi.KtContinueExpression ||
        this is org.jetbrains.kotlin.psi.KtBreakExpression ||
        this is org.jetbrains.kotlin.psi.KtThrowExpression ||
        this is org.jetbrains.kotlin.psi.KtReturnExpression

    private fun PsiElement.nextSiblings(): Sequence<PsiElement> = generateSequence(nextSibling) { it.nextSibling }
}
