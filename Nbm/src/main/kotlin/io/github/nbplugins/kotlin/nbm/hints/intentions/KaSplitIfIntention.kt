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
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Splits an `if` condition joined by `&&` or `||` into nested `if` statements.
 *
 * For example (&&):
 * ```kotlin
 * if (a && b) { body }
 * ```
 * becomes:
 * ```kotlin
 * if (a) { if (b) { body } }
 * ```
 *
 * For `||` with an exit statement in the then-branch, the split produces two consecutive ifs
 * (early-exit pattern).
 *
 * Applicable when the caret is on the `if` keyword, and the condition contains a `&&` or `||`
 * binary expression that is a valid split target.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaSplitIfIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findIfExpression(): KtIfExpression? =
        PsiTreeUtil.getParentOfType(psi, KtIfExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val ifExpr = findIfExpression() ?: return false
        return ifExpr.condition != null && ifExpr.then != null && findFirstValidOperator(ifExpr) != null
    }

    override fun getDescription(): String = "Split 'if' into two"

    override fun implement() {
        val ifExpr = findIfExpression() ?: return
        val operator = findFirstValidOperator(ifExpr) ?: return

        val condition = ifExpr.condition ?: return
        val expression = operator.parent as? KtBinaryExpression ?: return
        val thenBranch = ifExpr.then ?: return
        val elseBranch = ifExpr.`else`

        val rightText = KtPsiUtil.safeDeparenthesize(getRight(expression, condition)).text
        val leftText = KtPsiUtil.safeDeparenthesize(expression.left ?: return).text
        val elseClause = if (elseBranch != null) " else ${elseBranch.text}" else ""

        val startOffset = ifExpr.textRange.startOffset
        val endOffset = ifExpr.textRange.endOffset

        when (operator.getReferencedNameElementType()) {
            KtTokens.ANDAND -> {
                val innerIfText = "if ($rightText) ${thenBranch.text}$elseClause"
                val newText = "if ($leftText) { $innerIfText }$elseClause"
                doc.atomicChange {
                    remove(startOffset, endOffset - startOffset)
                    insertString(startOffset, newText, null)
                }
            }
            KtTokens.OROR -> {
                val container = ifExpr.parent
                if (container is KtBlockExpression && elseBranch == null && thenBranch.lastBlockStatementOrThis().isExitStatement()) {
                    val firstIfText = "if ($leftText) ${thenBranch.text}"
                    val secondIfText = "if ($rightText) ${thenBranch.text}"
                    doc.atomicChange {
                        remove(startOffset, endOffset - startOffset)
                        insertString(startOffset, "$firstIfText\n$secondIfText", null)
                    }
                } else {
                    val innerIfText = "if ($rightText) ${thenBranch.text}"
                    val newText = "if ($leftText) ${thenBranch.text} else $innerIfText"
                    doc.atomicChange {
                        remove(startOffset, endOffset - startOffset)
                        insertString(startOffset, newText, null)
                    }
                }
            }
        }
    }

    private fun KtExpression.isExitStatement(): Boolean =
        this is org.jetbrains.kotlin.psi.KtContinueExpression ||
        this is org.jetbrains.kotlin.psi.KtBreakExpression ||
        this is org.jetbrains.kotlin.psi.KtThrowExpression ||
        this is org.jetbrains.kotlin.psi.KtReturnExpression

    private fun KtExpression.lastBlockStatementOrThis(): KtExpression =
        if (this is KtBlockExpression) statements.lastOrNull() ?: this else this

    private fun findFirstValidOperator(ifExpr: KtIfExpression): KtOperationReferenceExpression? {
        val condition = ifExpr.condition ?: return null
        return PsiTreeUtil.findChildrenOfType(condition, KtOperationReferenceExpression::class.java)
            .firstOrNull { isOperatorValid(it, ifExpr) }
    }

    private fun isOperatorValid(element: KtOperationReferenceExpression, ifExpr: KtIfExpression): Boolean {
        val token = element.getReferencedNameElementType()
        if (token != KtTokens.ANDAND && token != KtTokens.OROR) return false

        var expression = element.parent as? KtBinaryExpression ?: return false
        if (expression.right == null || expression.left == null) return false

        while (true) {
            expression = expression.parent as? KtBinaryExpression ?: break
            if (expression.operationToken != token) return false
        }

        if (ifExpr.condition == null) return false
        if (!PsiTreeUtil.isAncestor(ifExpr.condition, element, false)) return false
        if (ifExpr.then == null) return false
        return true
    }

    private fun getRight(binaryExpr: KtBinaryExpression, condition: KtExpression): KtExpression {
        val conditionRange = condition.textRange
        val startOffset = binaryExpr.right!!.textRange.startOffset - conditionRange.startOffset
        val endOffset = conditionRange.length
        val rightString = condition.text.substring(startOffset, endOffset)
        return org.jetbrains.kotlin.psi.KtPsiFactory(binaryExpr.project).createExpression(rightString)
    }
}
