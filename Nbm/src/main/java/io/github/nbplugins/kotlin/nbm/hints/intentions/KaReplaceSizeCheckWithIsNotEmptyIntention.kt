/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.hints.atomicChange
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.intentions.ReplaceSizeCheckWithIsNotEmptyIntention].
 *
 * Offers "Replace size check with 'isNotEmpty'" when the caret is on a `collection.size == 0`
 * (or similar) pattern.  Uses K2 call resolution to verify the `size` / `length` property
 * instead of [org.jetbrains.kotlin.resolve.BindingContext].
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaReplaceSizeCheckWithIsNotEmptyIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var expression: KtBinaryExpression? = null

    override fun isApplicable(caretOffset: Int): Boolean {
        expression = psi.getNonStrictParentOfType(KtBinaryExpression::class.java) ?: return false
        val element = expression ?: return false
        val target = getTargetExpression(element) ?: return false

        return analyze(kaKtFile) { target.isSizeOrLengthK2() }
    }

    override fun getDescription(): String = "Replace size check with 'isNotEmpty'"

    override fun implement() {
        val element = expression ?: return
        val target = getTargetExpression(element) as? KtDotQualifiedExpression ?: return
        val newText = "${target.receiverExpression.text}.isNotEmpty()"

        val startOffset = element.textRange.startOffset
        val lengthToDelete = element.textLength

        doc.atomicChange {
            remove(startOffset, lengthToDelete)
            insertString(startOffset, newText, null)
        }
    }
}

/**
 * Returns true when [this] is a `receiver.size` or `receiver.length` property access on a
 * collection, array, map, or char sequence using K2 call resolution.
 */
context(org.jetbrains.kotlin.analysis.api.KaSession)
private fun KtExpression.isSizeOrLengthK2(): Boolean {
    if (this !is KtDotQualifiedExpression) return false
    val selector = selectorExpression ?: return false
    val selectorName = selector.text

    if (selectorName != "size" && selectorName != "length") return false

    // Use receiver expression type to avoid resolveCall() issues with property accesses.
    val receiverType = receiverExpression.expressionType ?: return false

    return when (selectorName) {
        "size" -> receiverType.isArrayOrPrimitiveArray ||
                receiverType.isSubtypeOfCollectionOrMap()
        "length" -> receiverType.isCharSequenceType || receiverType.isStringType
        else -> false
    }
}

context(org.jetbrains.kotlin.analysis.api.KaSession)
private fun org.jetbrains.kotlin.analysis.api.types.KaType.isSubtypeOfCollectionOrMap(): Boolean {
    val classSymbol = (this as? org.jetbrains.kotlin.analysis.api.types.KaClassType)?.symbol ?: return false
    val fqn = classSymbol.classId?.asFqNameString() ?: return false
    return fqn.startsWith("kotlin.collections")
}

private fun KtElement?.isZero() = this?.text == "0"
private fun KtElement?.isOne() = this?.text == "1"

private fun getTargetExpression(element: KtBinaryExpression): KtExpression? = when (element.operationToken) {
    KtTokens.EXCLEQ -> when {
        element.right.isZero() -> element.left
        element.left.isZero() -> element.right
        else -> null
    }
    KtTokens.GT -> if (element.right.isZero()) element.left else null
    KtTokens.LT -> if (element.left.isZero()) element.right else null
    KtTokens.GTEQ -> if (element.right.isOne()) element.left else null
    KtTokens.LTEQ -> if (element.left.isOne()) element.right else null
    else -> null
}
