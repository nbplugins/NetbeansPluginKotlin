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

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.allChildren

/**
 * Joins a multi-line function call argument list back onto a single line.
 *
 * For example:
 * ```
 * foo(
 *     1,
 *     "hello",
 *     true
 * )
 * ```
 * → `foo(1, "hello", true)`
 *
 * Applicable when the caret is inside a call argument list that spans multiple lines
 * and contains no end-of-line comments.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaJoinArgumentListIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findArgList(): KtValueArgumentList? =
        PsiTreeUtil.getParentOfType(psi, KtValueArgumentList::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val argList = findArgList() ?: return false
        if (argList.arguments.isEmpty()) return false
        if (argList.allChildren.any { it is PsiComment && it.node.elementType == KtTokens.EOL_COMMENT }) return false
        return argList.text.contains('\n')
    }

    override fun getDescription(): String = "Put arguments on one line"

    override fun implement() {
        val argList = findArgList() ?: return
        val args = argList.arguments
        if (args.isEmpty()) return

        val newText = "(${args.joinToString(", ") { it.text }})"
        val start = argList.textRange.startOffset
        val end = argList.textRange.endOffset
        doc.atomicChange {
            remove(start, end - start)
            insertString(start, newText, null)
        }
    }
}
