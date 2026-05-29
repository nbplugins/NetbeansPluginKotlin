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
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression

/**
 * Replaces an overloaded operator with the equivalent explicit function call.
 *
 * For example, `a + b` becomes `a.plus(b)`, `!a` becomes `a.not()`, and `a[i]` becomes `a.get(i)`.
 *
 * Applicable when the caret is on a unary expression, binary expression (with a supported operator),
 * or array access expression. Compound assignment (`+=`, etc.) and read-write operator expressions
 * are not supported.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaOperatorToFunctionIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findTarget(): KtExpression? {
        var current: PsiElement? = psi
        while (current != null && current !is KtFile) {
            val expr = current as? KtExpression
            if (expr != null && isApplicableTarget(expr)) return expr
            current = current.parent
        }
        return null
    }

    private fun isApplicableTarget(element: KtExpression): Boolean = when (element) {
        is KtUnaryExpression -> isApplicableUnary(element)
        is KtBinaryExpression -> isApplicableBinary(element)
        is KtArrayAccessExpression -> isApplicableArrayAccess(element)
        else -> false
    }

    private fun isApplicableUnary(element: KtUnaryExpression): Boolean {
        if (element.baseExpression == null) return false
        return when (element.operationReference.getReferencedNameElementType()) {
            KtTokens.PLUS, KtTokens.MINUS, KtTokens.EXCL -> true
            KtTokens.PLUSPLUS, KtTokens.MINUSMINUS -> {
                // Only applicable when not used as an expression (i.e., statement only).
                val parent = element.parent
                parent is KtBlockExpression || parent is KtLoopExpression || parent is KtFile
            }
            else -> false
        }
    }

    private fun isApplicableBinary(element: KtBinaryExpression): Boolean {
        if (element.left == null || element.right == null) return false
        val opRef = element.operationReference
        return when (opRef.getReferencedNameElementType()) {
            KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC,
            KtTokens.RANGE, KtTokens.RANGE_UNTIL,
            KtTokens.IN_KEYWORD, KtTokens.NOT_IN,
            KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ,
            KtTokens.GT, KtTokens.LT, KtTokens.GTEQ, KtTokens.LTEQ -> true
            KtTokens.EQEQ, KtTokens.EXCLEQ ->
                listOf(element.left, element.right).none { it?.node?.elementType == KtNodeTypes.NULL }
            KtTokens.EQ -> element.left is KtArrayAccessExpression
            else -> false
        }
    }

    private fun isApplicableArrayAccess(element: KtArrayAccessExpression): Boolean {
        // Skip compound read-write access (a[i] += x) — OperatorToFunctionConverter doesn't support it.
        val parent = element.parent
        if (parent is KtBinaryExpression && parent.operationToken == KtTokens.EQ && parent.left == element) return false
        return true
    }

    override fun isApplicable(caretOffset: Int): Boolean = findTarget() != null

    override fun getDescription(): String = "Replace overloaded operator with function call"

    override fun implement() {
        val target = findTarget() ?: return
        val resultText = when (target) {
            is KtBinaryExpression -> convertBinaryToText(target)
            is KtUnaryExpression -> convertUnaryToText(target)
            is KtArrayAccessExpression -> convertArrayAccessToText(target)
            else -> null
        } ?: return
        val startOffset = target.textRange.startOffset
        val length = target.textRange.length
        doc.atomicChange {
            remove(startOffset, length)
            insertString(startOffset, resultText, null)
        }
    }

    private fun convertBinaryToText(element: KtBinaryExpression): String? {
        val left = element.left?.text ?: return null
        val right = element.right?.text ?: return null
        return when (element.operationToken) {
            KtTokens.PLUS -> "$left.plus($right)"
            KtTokens.MINUS -> "$left.minus($right)"
            KtTokens.MUL -> "$left.times($right)"
            KtTokens.DIV -> "$left.div($right)"
            KtTokens.PERC -> "$left.rem($right)"
            KtTokens.RANGE -> "$left.rangeTo($right)"
            KtTokens.RANGE_UNTIL -> "$left.rangeUntil($right)"
            KtTokens.IN_KEYWORD -> "$right.contains($left)"
            KtTokens.NOT_IN -> "!$right.contains($left)"
            KtTokens.PLUSEQ -> "$left.plusAssign($right)"
            KtTokens.MINUSEQ -> "$left.minusAssign($right)"
            KtTokens.MULTEQ -> "$left.timesAssign($right)"
            KtTokens.DIVEQ -> "$left.divAssign($right)"
            KtTokens.PERCEQ -> "$left.remAssign($right)"
            KtTokens.GT -> "$left.compareTo($right) > 0"
            KtTokens.LT -> "$left.compareTo($right) < 0"
            KtTokens.GTEQ -> "$left.compareTo($right) >= 0"
            KtTokens.LTEQ -> "$left.compareTo($right) <= 0"
            KtTokens.EQEQ -> "$left.equals($right)"
            KtTokens.EXCLEQ -> "!$left.equals($right)"
            else -> null
        }
    }

    private fun convertUnaryToText(element: KtUnaryExpression): String? {
        val base = element.baseExpression?.text ?: return null
        return when (element.operationToken) {
            KtTokens.PLUS -> "$base.unaryPlus()"
            KtTokens.MINUS -> "$base.unaryMinus()"
            KtTokens.EXCL -> "$base.not()"
            KtTokens.PLUSPLUS -> "$base.inc()"
            KtTokens.MINUSMINUS -> "$base.dec()"
            else -> null
        }
    }

    private fun convertArrayAccessToText(element: KtArrayAccessExpression): String? {
        val array = element.arrayExpression?.text ?: return null
        val indices = element.indexExpressions.joinToString(", ") { it.text }
        return "$array.get($indices)"
    }
}
