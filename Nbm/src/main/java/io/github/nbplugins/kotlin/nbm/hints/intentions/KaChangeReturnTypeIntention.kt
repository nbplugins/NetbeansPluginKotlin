@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
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
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.hints.atomicChange
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.types.Variance
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.intentions.ChangeReturnTypeIntention].
 *
 * Offers "Change function return type" when the caret is inside an expression whose type
 * differs from the enclosing function's declared return type.  Uses K2
 * [org.jetbrains.kotlin.analysis.api.types.KaType] instead of
 * [org.jetbrains.kotlin.resolve.BindingContext].
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaChangeReturnTypeIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var function: KtFunction? = null
    private var newReturnType: KaType? = null
    private var renderedType: String = ""

    override fun isApplicable(caretOffset: Int): Boolean {
        val expression = psi.getNonStrictParentOfType(KtExpression::class.java) ?: return false
        val ktFunction = expression.getNonStrictParentOfType(KtFunction::class.java) ?: return false

        // Don't trigger when psi is on the function declaration itself (KtFunction extends
        // KtExpression, so getNonStrictParentOfType can return the function as its own expression).
        if (expression === ktFunction) return false

        if (ktFunction.typeReference == null) return false  // no declared return type to change

        // For block-body functions, only trigger on explicit return expressions — not on
        // arbitrary statements whose type happens to differ from the declared return type.
        if (ktFunction.hasBlockBody()) {
            val returnExpr = expression.getNonStrictParentOfType(KtReturnExpression::class.java)
                ?: return false
            if (returnExpr.returnedExpression == null) return false
        }

        val (exprType, funcReturnType) = analyze(kaKtFile) {
            val et = expression.expressionType ?: return@analyze null to null
            val ft = ktFunction.returnType
            et to ft
        }

        if (exprType == null || funcReturnType == null) return false

        // Applicable when the expression type differs from the declared return type
        val typesMatch = analyze(kaKtFile) { exprType.isEqualTo(funcReturnType) }
        if (typesMatch) return false

        function = ktFunction
        newReturnType = exprType
        renderedType = analyze(kaKtFile) {
            exprType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        }
        return true
    }

    override fun getDescription(): String {
        val functionName = function?.name
        return if (functionName != null) {
            "Change '$functionName' function return type to '$renderedType'"
        } else {
            "Change function return type to '$renderedType'"
        }
    }

    override fun implement() {
        val ktFunction = function ?: return
        val oldTypeRef = ktFunction.typeReference

        if (oldTypeRef != null) {
            val startOffset = oldTypeRef.textRange.startOffset
            val endOffset = oldTypeRef.textRange.endOffset
            doc.atomicChange {
                remove(startOffset, endOffset - startOffset)
                insertString(startOffset, renderedType, null)
            }
        } else {
            val anchor = ktFunction.valueParameterList ?: return
            doc.insertString(anchor.textRange.endOffset, ": $renderedType", null)
        }
    }
}
