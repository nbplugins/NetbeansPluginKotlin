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
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.hints.atomicChange
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.psiUtil.contentRange
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.reformatting.format
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.intentions.ConvertTryFinallyToUseCallIntention].
 *
 * Suggests replacing try-finally blocks that call `close()` on a resource with `.use { }`.
 * Uses K2 [org.jetbrains.kotlin.analysis.api.analyze] for call resolution instead of
 * [org.jetbrains.kotlin.resolve.BindingContext].
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertTryFinallyToUseCallIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var tryExpression: KtTryExpression? = null

    override fun isApplicable(caretOffset: Int): Boolean {
        tryExpression = psi.getNonStrictParentOfType(KtTryExpression::class.java) ?: return false
        val element = tryExpression ?: return false

        val finallySection = element.finallyBlock ?: return false
        val finallyExpression = finallySection.finalExpression.statements.singleOrNull() ?: return false
        if (element.catchClauses.isNotEmpty()) return false

        return analyze(kaKtFile) {
            val call = finallyExpression.resolveToCall()?.successfulFunctionCallOrNull() ?: return@analyze false
            val symbol = call.symbol
            if (symbol.callableId?.callableName?.asString() != "close") return@analyze false
            // Ensure there's a receiver (this.close() or resource.close())
            val receiver = call.partiallyAppliedSymbol.dispatchReceiver
                ?: call.partiallyAppliedSymbol.extensionReceiver
                ?: return@analyze false

            // Receiver must be a val variable or `this`
            val receiverExpr = (finallyExpression as? KtQualifiedExpression)?.receiverExpression
            if (receiverExpr is KtThisExpression) return@analyze true
            val refExpr = receiverExpr as? KtNameReferenceExpression ?: return@analyze false
            val varSymbol = refExpr.resolveToCall()
                ?.let { it as? org.jetbrains.kotlin.analysis.api.resolution.KaVariableAccessCall }
                ?.partiallyAppliedSymbol?.symbol as? KaVariableSymbol ?: return@analyze false
            !varSymbol.isVal.not()  // must be val (isVal == true)
        }
    }

    override fun getDescription(): String = "Convert try-finally to .use()"

    override fun implement() {
        val element = tryExpression ?: return

        val finallySection = element.finallyBlock!!
        val finallyExpression = finallySection.finalExpression.statements.single()
        val finallyExpressionReceiver = (finallyExpression as? KtQualifiedExpression)?.receiverExpression
        val resourceReference = finallyExpressionReceiver as? KtNameReferenceExpression
        val resourceName = resourceReference?.getReferencedNameAsName()

        val useExpression = StringBuilder()

        with(useExpression) {
            if (resourceName != null) {
                append(resourceName).append(".")
            } else if (finallyExpressionReceiver is KtThisExpression) {
                append(finallyExpressionReceiver.text).append(".")
            }

            append("use {")

            if (resourceName != null) {
                append(resourceName).append("->")
            }
            append("\n")

            element.tryBlock.contentRange().forEach { append(it.text).append("\n") }

            append("}")
        }

        doc.atomicChange {
            remove(element.textRange.startOffset, element.textLength)
            insertString(element.textRange.startOffset, useExpression.toString(), null)
            format(this, element.textRange.startOffset)
        }
    }
}
