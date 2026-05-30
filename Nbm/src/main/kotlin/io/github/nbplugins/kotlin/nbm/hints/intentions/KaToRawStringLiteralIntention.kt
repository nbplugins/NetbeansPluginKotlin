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
import org.jetbrains.kotlin.idea.codeinsights.impl.base.canBeConvertedToStringLiteral
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Converts an escaped string template to a raw triple-quoted string literal.
 *
 * Applicable when the caret is on a double-quoted `KtStringTemplateExpression` that can be
 * safely converted to a raw string: it must not already be a raw string, must not contain
 * the `"""` sequence, and all escape sequences must be representable as raw characters.
 *
 * For example: `"Hello\nWorld"` → `"""Hello\nWorld"""` (keeping the content as-is, with
 * escape sequences unescaped so they render literally in the raw string).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaToRawStringLiteralIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findTemplateExpr(): KtStringTemplateExpression? =
        PsiTreeUtil.getParentOfType(psi, KtStringTemplateExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean =
        findTemplateExpr()?.canBeConvertedToStringLiteral() == true

    override fun getDescription(): String = "Convert to raw string literal"

    override fun implement() {
        val element = findTemplateExpr() ?: return
        val rawText = convertToRaw(element)
        val start = element.textRange.startOffset
        doc.atomicChange {
            remove(start, element.textRange.length)
            insertString(start, rawText, null)
        }
    }
}
