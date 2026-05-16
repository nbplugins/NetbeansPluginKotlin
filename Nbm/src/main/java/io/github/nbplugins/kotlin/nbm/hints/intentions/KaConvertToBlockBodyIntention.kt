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
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.hints.atomicChange
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.reformatting.format
import org.jetbrains.kotlin.types.Variance
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.intentions.ConvertToBlockBodyIntention].
 *
 * Converts an expression-body function or accessor to a block body.
 * Uses [org.jetbrains.kotlin.analysis.api.types.KaType] for return/body type checks instead
 * of [org.jetbrains.kotlin.resolve.BindingContext].
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertToBlockBodyIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    override fun isApplicable(caretOffset: Int): Boolean {
        val declaration = PsiTreeUtil.getParentOfType(psi, KtDeclarationWithBody::class.java) ?: return false
        if (declaration is KtFunctionLiteral || declaration.hasBlockBody() || !declaration.hasBody()) return false

        return when (declaration) {
            is KtNamedFunction -> analyze(kaKtFile) {
                val returnType = declaration.returnType
                // Reject if undeclared return type is an error type (unknown)
                if (!declaration.hasDeclaredReturnType() && returnType is KaErrorType) return@analyze false
                true
            }
            is KtPropertyAccessor -> true
            else -> false
        }
    }

    override fun getDescription(): String = "Convert to block body"

    override fun implement() {
        val declaration = PsiTreeUtil.getParentOfType(psi, KtDeclarationWithBody::class.java) ?: return

        val (shouldSpecifyType, returnTypeText) = analyze(kaKtFile) {
            when (declaration) {
                is KtNamedFunction -> {
                    val returnType = declaration.returnType
                    val needType = !declaration.hasDeclaredReturnType() && !returnType.isUnitType
                    needType to returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                }
                else -> false to ""
            }
        }

        val factory = KtPsiFactory(declaration)
        val body = declaration.bodyExpression ?: return

        val (isUnit, isNothing) = analyze(kaKtFile) {
            val bt = body.expressionType
            (bt?.isUnitType ?: false) to (bt?.isNothingType ?: false)
        }

        val returnsValue = when (declaration) {
            is KtNamedFunction -> !isUnit && !isNothing
            is KtPropertyAccessor -> declaration.isGetter
            else -> false
        }

        val newBody = if (returnsValue) {
            factory.createBlock("return ${body.text}")
        } else {
            factory.createBlock(body.text)
        }

        var newBodyText = newBody.node.text
        val anchorToken = declaration.equalsToken ?: run {
            KotlinLogger.INSTANCE.logInfo("ConvertToBlockBody: equalsToken is null for ${declaration.name}, hasBlockBody=${declaration.hasBlockBody()}, bodyExpr=${body.text}")
            return
        }
        if (anchorToken.nextSibling !is PsiWhiteSpace) {
            newBodyText = "${factory.createWhiteSpace().text}$newBodyText"
        }

        val startOffset = anchorToken.textRange.startOffset
        val endOffset = body.textRange.endOffset

        // Compute absolute offsets before any document modification.
        val paramListEnd = (declaration as? KtNamedFunction)?.valueParameterList?.textRange?.endOffset
        val declStart = declaration.textRange.startOffset

        KotlinLogger.INSTANCE.logInfo("ConvertToBlockBody: doc=${doc.javaClass.name} paramListEnd=$paramListEnd startOffset=$startOffset endOffset=$endOffset newBodyText=$newBodyText shouldSpecify=$shouldSpecifyType returnType=$returnTypeText")
        doc.atomicChange {
            // Insert type annotation first (lower offset) so subsequent offsets stay valid.
            if (shouldSpecifyType && paramListEnd != null) {
                insertString(paramListEnd, ": $returnTypeText", null)
            }
            val shift = if (shouldSpecifyType && paramListEnd != null) (": $returnTypeText").length else 0
            KotlinLogger.INSTANCE.logInfo("ConvertToBlockBody: removing $shift+$startOffset len ${endOffset - startOffset}")
            remove(startOffset + shift, endOffset - startOffset)
            insertString(startOffset + shift, newBodyText, null)
        }
        KotlinLogger.INSTANCE.logInfo("ConvertToBlockBody: after atomicChange doc len=${doc.length} text=${doc.getText(0, doc.length).replace("\n","\\n")}")
        // Format outside atomicChange: format() replaces the entire document, which must not
        // happen inside the same atomic edit that just inserted our text.
        val beforeFormat = doc.getText(0, doc.length)
        format(doc, declStart + if (shouldSpecifyType && paramListEnd != null) (": $returnTypeText").length else 0)
        KotlinLogger.INSTANCE.logInfo("ConvertToBlockBody: after format doc len=${doc.length} text=${doc.getText(0, doc.length).replace("\n","\\n")}")
    }
}
