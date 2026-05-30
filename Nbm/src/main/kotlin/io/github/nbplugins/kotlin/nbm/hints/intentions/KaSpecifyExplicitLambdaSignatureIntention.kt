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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.refactoring.util.getExplicitLambdaSignature
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * Adds an explicit parameter type signature to a lambda expression.
 *
 * For example, `{ it + 1 }` assigned to a `(Int) -> Int` variable becomes `{ x: Int -> x + 1 }`.
 *
 * Applicable when the lambda has no arrow (implicit single parameter `it`), or at least one
 * parameter has no explicit type reference, and the K2 Analysis API can render the parameter types.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaSpecifyExplicitLambdaSignatureIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var cachedSignature: String? = null

    private fun findLambda(): KtLambdaExpression? =
        PsiTreeUtil.getParentOfType(psi, KtLambdaExpression::class.java, false)
            ?.takeIf { lambda ->
                lambda.functionLiteral.arrow == null ||
                        !lambda.valueParameters.all { it.typeReference != null }
            }

    override fun isApplicable(caretOffset: Int): Boolean {
        val lambda = findLambda() ?: return false
        val sig = analyze(kaKtFile) { getExplicitLambdaSignature(lambda) } ?: return false
        cachedSignature = sig
        return true
    }

    override fun getDescription(): String = "Specify explicit lambda signature"

    override fun implement() {
        val lambda = findLambda() ?: return
        val sig = cachedSignature
            ?: analyze(kaKtFile) { getExplicitLambdaSignature(lambda) }
            ?: return
        val functionLiteral = lambda.functionLiteral
        val existingParams = functionLiteral.valueParameterList
        if (existingParams != null) {
            // Replace the existing parameter list text with the new typed signature.
            val startOffset = existingParams.textRange.startOffset
            val length = existingParams.textRange.length
            doc.atomicChange {
                remove(startOffset, length)
                insertString(startOffset, sig, null)
            }
        } else {
            // No parameter list and no arrow — insert " sig ->" right after the opening brace.
            val insertOffset = functionLiteral.lBrace.textRange.endOffset
            doc.atomicChange {
                insertString(insertOffset, " $sig ->", null)
            }
        }
    }
}
