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
import org.jetbrains.kotlin.idea.codeinsights.impl.base.canBeConvertedToStringLiteral
import org.jetbrains.kotlin.idea.codeinsights.impl.base.containNoNewLine
import org.jetbrains.kotlin.idea.codeinsights.impl.base.isFirstStringPlusExpressionWithoutNewLineInOperands
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Converts a string concatenation expression to a raw triple-quoted string literal.
 *
 * Applicable when the caret is on the outermost string-plus binary expression in a chain,
 * the expression contains no newlines, and all embedded string template operands can be
 * converted to a raw string literal (i.e., the resulting content contains no `"""`).
 *
 * For example: `"Hello " + name + "!\n"` → `"""Hello ${name}!"""` (if there are no triple-quote
 * sequences in any of the operands).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertToRawStringTemplateIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findBinaryExpr(): KtBinaryExpression? =
        PsiTreeUtil.getParentOfType(psi, KtBinaryExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val expr = findBinaryExpr() ?: return false
        if (expr.operationToken != KtTokens.PLUS || !expr.containNoNewLine()) return false
        if (!analyze(kaKtFile) { isFirstStringPlusExpressionWithoutNewLineInOperands(expr) }) return false
        return PsiTreeUtil.collectElementsOfType(expr, KtStringTemplateExpression::class.java)
            .all { it.canBeConvertedToStringLiteral() }
    }

    override fun getDescription(): String = "Convert concatenation to raw string"

    override fun implement() {
        val expr = findBinaryExpr() ?: return
        val template = analyze(kaKtFile) { buildStringTemplateForBinaryExpression(expr) }
        val rawText = convertToRaw(template)
        val start = expr.textRange.startOffset
        doc.atomicChange {
            remove(start, expr.textRange.length)
            insertString(start, rawText, null)
        }
    }
}
