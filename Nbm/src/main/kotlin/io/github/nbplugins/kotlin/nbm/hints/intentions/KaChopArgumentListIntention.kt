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
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import io.github.nbplugins.kotlin.nbm.reformatting.format
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Puts each argument of a function call on its own line.
 *
 * For example: `foo(1, "hello", true)` →
 * ```
 * foo(
 *     1,
 *     "hello",
 *     true
 * )
 * ```
 *
 * Applicable when the caret is inside a call argument list with two or more arguments
 * that are not already all on separate lines.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaChopArgumentListIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findArgList(): KtValueArgumentList? =
        PsiTreeUtil.getParentOfType(psi, KtValueArgumentList::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val argList = findArgList() ?: return false
        val args = argList.arguments
        if (args.size <= 1) return false
        return args.any { arg ->
            val prev = arg.prevSibling
            prev == null || !(prev is PsiWhiteSpace && prev.textContains('\n'))
        }
    }

    override fun getDescription(): String = "Put arguments on separate lines"

    override fun implement() {
        val argList = findArgList() ?: return
        val args = argList.arguments
        if (args.size <= 1) return

        val fileText = kaKtFile.text
        val listStart = argList.textRange.startOffset
        val lineStart = fileText.lastIndexOf('\n', listStart - 1) + 1
        val lineIndent = fileText.substring(lineStart, listStart).takeWhile { it == ' ' || it == '\t' }
        val indentStep = detectIndentStep(fileText, listStart)
        val argIndent = lineIndent + indentStep

        val newText = buildString {
            append("(\n")
            for ((i, arg) in args.withIndex()) {
                append(argIndent)
                append(arg.text)
                if (i < args.size - 1) append(",")
                append("\n")
            }
            append(lineIndent)
            append(")")
        }

        val start = listStart
        val end = argList.textRange.endOffset
        doc.atomicChange {
            remove(start, end - start)
            insertString(start, newText, null)
        }
        format(doc, start, lineStart, start + newText.length)
    }

}
