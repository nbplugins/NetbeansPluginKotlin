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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.idea.base.psi.getSingleUnwrappedStatementOrThis
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtPsiUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Converts an if/else-if chain to a `when` expression.
 *
 * For example:
 * ```kotlin
 * if (a == 1) "one"
 * else if (a == 2) "two"
 * else "other"
 * ```
 * becomes:
 * ```kotlin
 * when {
 *     a == 1 -> "one"
 *     a == 2 -> "two"
 *     else -> "other"
 * }
 * ```
 *
 * Applicable when the caret is on the `if` keyword of the outermost if-expression in a chain
 * and the then-branch is non-null.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaIfToWhenIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findIfExpression(): KtIfExpression? =
        PsiTreeUtil.getParentOfType(psi, KtIfExpression::class.java, false)
            ?.topmostIfExpression()

    override fun isApplicable(caretOffset: Int): Boolean {
        val ifExpr = findIfExpression() ?: return false
        return ifExpr.then != null
    }

    override fun getDescription(): String = "Replace 'if' with 'when'"

    override fun implement() {
        val ifExpr = findIfExpression() ?: return
        val startOffset = ifExpr.textRange.startOffset
        val endOffset = ifExpr.textRange.endOffset

        val whenText = buildWhenText(ifExpr) ?: return
        doc.atomicChange {
            remove(startOffset, endOffset - startOffset)
            insertString(startOffset, whenText, null)
        }
    }

    private fun buildWhenText(ifExpression: KtIfExpression): String? {
        val sb = StringBuilder("when {\n")
        var current: KtIfExpression? = ifExpression
        while (current != null) {
            val condition = current.condition ?: return null
            val branches = buildList { addOrBranches(condition) }
            sb.append(branches.joinToString(" || ") { it.text })
            sb.append(" -> ")
            sb.append(current.then?.text ?: return null)
            sb.append("\n")

            val elseBranch = current.`else`
            if (elseBranch == null) break
            if (elseBranch is KtIfExpression) {
                current = elseBranch
            } else {
                sb.append("else -> ")
                sb.append(elseBranch.getSingleUnwrappedStatementOrThis().text)
                sb.append("\n")
                current = null
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun MutableList<KtExpression>.addOrBranches(expression: KtExpression) {
        if (expression is KtBinaryExpression && expression.operationToken == KtTokens.OROR) {
            val left = expression.left
            val right = expression.right
            if (left != null && right != null) {
                addOrBranches(left)
                addOrBranches(right)
                return
            }
        }
        add(KtPsiUtil.safeDeparenthesize(expression, true))
    }

    private fun KtIfExpression.topmostIfExpression(): KtIfExpression {
        var target = this
        while (true) {
            val container = target.parent as? org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody ?: break
            val parent = container.parent as? KtIfExpression ?: break
            if (parent.`else` != target) break
            target = parent
        }
        return target
    }
}
