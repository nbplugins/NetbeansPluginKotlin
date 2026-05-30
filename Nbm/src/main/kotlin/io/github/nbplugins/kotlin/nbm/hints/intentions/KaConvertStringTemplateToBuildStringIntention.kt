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
import org.jetbrains.kotlin.idea.base.psi.isInsideAnnotationEntryArgumentList
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Converts a regular string template expression to a `buildString { }` call.
 *
 * Applicable when the caret is on a double-quoted (non-raw) `KtStringTemplateExpression` that
 * is not inside an annotation argument list.
 *
 * For example:
 * ```kotlin
 * "Hello ${name}!"
 * ```
 * becomes:
 * ```kotlin
 * buildString {
 *     append("Hello ")
 *     append(name)
 *     append("!")
 * }
 * ```
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertStringTemplateToBuildStringIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findTemplateExpr(): KtStringTemplateExpression? =
        PsiTreeUtil.getParentOfType(psi, KtStringTemplateExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val expr = findTemplateExpr() ?: return false
        return !expr.text.startsWith("\"\"\"") && !expr.isInsideAnnotationEntryArgumentList()
    }

    override fun getDescription(): String = "Convert string template to 'buildString'"

    override fun implement() {
        val element = findTemplateExpr() ?: return
        val sb = StringBuilder("buildString {\n")
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                sb.append("append(\"$literal\")\n")
                literal.clear()
            }
        }

        for (entry in element.entries) {
            when (entry) {
                is KtStringTemplateEntryWithExpression -> {
                    flushLiteral()
                    entry.expression?.text?.let { sb.append("append($it)\n") }
                }
                else -> literal.append(entry.text)
            }
        }
        flushLiteral()
        sb.append("}")

        val start = element.textRange.startOffset
        doc.atomicChange {
            remove(start, element.textRange.length)
            insertString(start, sb.toString(), null)
        }
    }
}
