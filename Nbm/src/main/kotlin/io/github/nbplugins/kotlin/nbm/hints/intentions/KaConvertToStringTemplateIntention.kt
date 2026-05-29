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
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.codeinsights.impl.base.buildStringTemplateForBinaryExpression
import org.jetbrains.kotlin.idea.codeinsights.impl.base.containNoNewLine
import org.jetbrains.kotlin.idea.codeinsights.impl.base.isFirstStringPlusExpressionWithoutNewLineInOperands
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtFile
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Converts a string concatenation expression (`"a" + x`) to a string template (`"a${x}"`).
 *
 * Applicable when the caret is on a binary `+` expression that is the outermost string-plus
 * expression in a chain, contains no newlines in operands, and K2 confirms all operands are
 * connected by string-producing `+` operators.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertToStringTemplateIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findBinaryExpr(): KtBinaryExpression? =
        PsiTreeUtil.getParentOfType(psi, KtBinaryExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val expr = findBinaryExpr() ?: return false
        if (expr.operationToken != KtTokens.PLUS || !expr.containNoNewLine()) return false
        return analyze(kaKtFile) { isFirstStringPlusExpressionWithoutNewLineInOperands(expr) }
    }

    override fun getDescription(): String = "Convert concatenation to template"

    override fun implement() {
        val expr = findBinaryExpr() ?: return
        val newText = analyze(kaKtFile) { buildStringTemplateForBinaryExpression(expr).text }
        val start = expr.textRange.startOffset
        doc.atomicChange {
            remove(start, expr.textRange.length)
            insertString(start, newText, null)
        }
    }
}
