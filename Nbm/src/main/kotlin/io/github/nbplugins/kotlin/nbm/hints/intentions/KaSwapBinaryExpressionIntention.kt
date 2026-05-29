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
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.idea.util.PsiPrecedences
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * Swaps the left and right operands of a supported binary expression.
 *
 * For example, `a + b` becomes `b + a`, and `a > b` becomes `b < a` (operator is flipped to
 * preserve semantics when the operands of a comparison are swapped).
 *
 * Applicable when the caret is on the operator reference of a binary expression whose operator
 * is in the supported set (`+`, `*`, `||`, `&&`, `==`, `!=`, `>`, `<`, `>=`, `<=`, and their
 * named equivalents).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaSwapBinaryExpressionIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findExpression(): KtBinaryExpression? =
        PsiTreeUtil.getParentOfType(psi, KtBinaryExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val element = findExpression() ?: return false
        val opRef = element.operationReference
        if (!opRef.textRange.containsOffset(caretOffset)) return false
        if (leftSubject(element) == null || rightSubject(element) == null) return false
        val opToken = element.operationToken
        val opText = opRef.text
        return opToken in SUPPORTED_OPERATIONS || (opToken == KtTokens.IDENTIFIER && opText in SUPPORTED_NAMES)
    }

    override fun getDescription(): String {
        val opText = findExpression()?.operationReference?.text ?: "expression"
        return "Flip '$opText'"
    }

    override fun implement() {
        val element = findExpression() ?: return
        val left = leftSubject(element) ?: return
        val right = rightSubject(element) ?: return

        val convertedOp = when (val op = element.operationReference.text) {
            ">" -> "<"
            "<" -> ">"
            "<=" -> ">="
            ">=" -> "<="
            else -> op
        }

        val newText = "${right.text} $convertedOp ${left.text}"
        val startOffset = element.textRange.startOffset
        val length = element.textRange.length
        doc.atomicChange {
            remove(startOffset, length)
            insertString(startOffset, newText, null)
        }
    }

    private fun leftSubject(element: KtBinaryExpression): KtExpression? =
        firstDescendantOfTighterPrecedence(element.left, PsiPrecedences.getPrecedence(element), KtBinaryExpression::getRight)

    private fun rightSubject(element: KtBinaryExpression): KtExpression? =
        firstDescendantOfTighterPrecedence(element.right, PsiPrecedences.getPrecedence(element), KtBinaryExpression::getLeft)

    private fun firstDescendantOfTighterPrecedence(
        expression: KtExpression?,
        precedence: Int,
        getChild: KtBinaryExpression.() -> KtExpression?,
    ): KtExpression? {
        if (expression is KtBinaryExpression) {
            val exprPrec = PsiPrecedences.getPrecedence(expression)
            if (!PsiPrecedences.isTighter(exprPrec, precedence)) {
                return firstDescendantOfTighterPrecedence(expression.getChild(), precedence, getChild)
            }
        }
        return expression
    }

    companion object {
        private val SUPPORTED_OPERATIONS: Set<KtSingleValueToken> = setOf(
            KtTokens.PLUS, KtTokens.MUL, KtTokens.OROR, KtTokens.ANDAND,
            KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ,
            KtTokens.GT, KtTokens.LT, KtTokens.GTEQ, KtTokens.LTEQ,
        )

        private val SUPPORTED_NAMES: Set<String> = setOf("xor", "or", "and", "equals")
    }
}
