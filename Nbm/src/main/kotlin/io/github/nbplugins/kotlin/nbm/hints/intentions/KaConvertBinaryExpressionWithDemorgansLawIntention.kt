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
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.idea.codeinsight.utils.DemorgansLawUtils.splitBooleanSequence
import org.jetbrains.kotlin.idea.codeinsight.utils.DemorgansLawUtils.topmostBinaryExpression
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.util.match

/**
 * Applies De Morgan's law to a boolean `&&` or `||` expression.
 *
 * For example, `a && b` becomes `!((!a) || (!b))`, and
 * `!(a && b)` becomes `!a || !b` (unwraps the outer negation).
 *
 * Applicable when the caret is on a binary expression whose topmost form is `&&` or `||`.
 * Since `&&` and `||` cannot be overloaded in Kotlin, no K2 type check is needed.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertBinaryExpressionWithDemorgansLawIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findTopmost(): KtBinaryExpression? =
        PsiTreeUtil.getParentOfType(psi, KtBinaryExpression::class.java, false)
            ?.topmostBinaryExpression()

    override fun isApplicable(caretOffset: Int): Boolean {
        val topmost = findTopmost() ?: return false
        val op = topmost.operationToken
        if (op != KtTokens.ANDAND && op != KtTokens.OROR) return false
        // && and || cannot be overloaded in Kotlin, so a syntactic check is sufficient.
        return splitBooleanSequence(topmost) != null
    }

    override fun getDescription(): String {
        val topmost = findTopmost() ?: return "Apply De Morgan's law"
        return when (topmost.operationToken) {
            KtTokens.ANDAND -> "Replace '&&' with '||'"
            KtTokens.OROR -> "Replace '||' with '&&'"
            else -> "Apply De Morgan's law"
        }
    }

    override fun implement() {
        val topmost = findTopmost() ?: return
        val operands = splitBooleanSequence(topmost) ?: return

        val operatorText = when (topmost.operationToken) {
            KtTokens.ANDAND -> "||"
            KtTokens.OROR -> "&&"
            else -> return
        }

        // Build negated operand texts without PSI tree modification.
        // splitBooleanSequence returns operands in reversed order (right-to-left traversal),
        // so reverse them back to get left-to-right order.
        val negatedTexts = operands.asReversed().map { operand -> negateExprText(operand) }
        val innerText = negatedTexts.joinToString(" $operatorText ")

        // If the topmost is inside !(expr), replace the outer !(expr) with the inner expression.
        // Otherwise, wrap the inner expression with !().
        val negatedParent = topmost.parents
            .match(KtParenthesizedExpression::class, last = KtPrefixExpression::class)
            ?.takeIf { it.operationReference.getReferencedNameElementType() == KtTokens.EXCL }

        val target = negatedParent ?: topmost
        val resultText = if (negatedParent != null) innerText else "!($innerText)"

        val startOffset = target.textRange.startOffset
        val length = target.textRange.length
        doc.atomicChange {
            remove(startOffset, length)
            insertString(startOffset, resultText, null)
        }
    }

    /** Returns text for the negation of [expr], eliminating double negation (`!!e` → `e`). */
    private fun negateExprText(expr: KtExpression): String {
        if (expr is KtPrefixExpression && expr.operationToken == KtTokens.EXCL) {
            // !!x → x; unwrap one level
            return expr.baseExpression?.text ?: "!(${expr.text})"
        }
        // Wrap binary expressions to preserve precedence.
        return if (expr is KtBinaryExpression) "!(${expr.text})" else "!${expr.text}"
    }
}
