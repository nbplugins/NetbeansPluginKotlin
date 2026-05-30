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
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtOperationExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Converts a string template expression (`"a${x}b"`) to a concatenated string (`"a" + x + "b"`).
 *
 * Applicable when the caret is on a `KtStringTemplateExpression` that has a closing quote and
 * contains at least one template entry with an embedded expression. The first expression is
 * optionally wrapped with `.toString()` if the K2 Analysis API determines it is not a `String`.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertToConcatenatedStringIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findTemplateExpr(): KtStringTemplateExpression? =
        PsiTreeUtil.getParentOfType(psi, KtStringTemplateExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val expr = findTemplateExpr() ?: return false
        if (expr.lastChild?.node?.elementType != KtTokens.CLOSING_QUOTE) return false
        return expr.entries.any { it is KtStringTemplateEntryWithExpression }
    }

    override fun getDescription(): String = "Convert string template to concatenated string"

    override fun implement() {
        val element = findTemplateExpr() ?: return
        val tripleQuoted = element.text.startsWith("\"\"\"")
        val quote = if (tripleQuoted) "\"\"\"" else "\""

        val entries = element.entries.filterNot { it is KtStringTemplateEntryWithExpression && it.expression == null }

        val firstExprEntry = entries.firstOrNull() as? KtStringTemplateEntryWithExpression
        val convertFirstExplicitly = firstExprEntry?.expression?.let { expr ->
            !analyze(kaKtFile) { expr.expressionType?.isStringType == true }
        } ?: false

        val entryTexts = entries.mapIndexed { index, entry ->
            val text = entryToSeparateString(entry, quote, convertExplicitly = (index == 0) && convertFirstExplicitly)
            val isStr = text.startsWith(quote) && text.endsWith(quote) && entry.isStringLiteralEntry()
            text to isStr
        }

        val parts = entryTexts.foldIndexed(mutableListOf<String>()) { index, acc, (curr, currIsStr) ->
            val prevIsStr = entryTexts.getOrNull(index - 1)?.second ?: false
            val nextIsStr = entryTexts.getOrNull(index + 1)?.second ?: false
            var text = curr
            if (currIsStr && nextIsStr) text = text.removeSuffix(quote)
            if (currIsStr && prevIsStr) {
                acc[acc.lastIndex] += text.removePrefix(quote)
            } else {
                acc.add(text)
            }
            acc
        }

        val newText = parts.joinToString("+")
        val start = element.textRange.startOffset
        doc.atomicChange {
            remove(start, element.textRange.length)
            insertString(start, newText, null)
        }
    }

    private fun entryToSeparateString(entry: KtStringTemplateEntry, quote: String, convertExplicitly: Boolean): String {
        if (entry !is KtStringTemplateEntryWithExpression) return "$quote${entry.text}$quote"
        val expression = entry.expression ?: return ""
        val text = if (needsParenthesis(expression)) "(${expression.text})" else expression.text
        return if (convertExplicitly) "$text.toString()" else text
    }

    private fun needsParenthesis(expression: com.intellij.psi.PsiElement): Boolean = when (expression) {
        is KtPostfixExpression -> false
        is KtAnnotatedExpression, is KtLabeledExpression, is KtOperationExpression -> true
        is KtIfExpression -> expression.`else` !is KtBlockExpression
        else -> false
    }

    private fun KtStringTemplateEntry.isStringLiteralEntry(): Boolean =
        expression == null || expression is KtStringTemplateExpression
}
