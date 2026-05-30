/*******************************************************************************
 * Copyright 2000-2023 JetBrains s.r.o.
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
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.codeinsight.utils.createArgumentWithoutName
import org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions.RemoveArgumentNamesUtils
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile

/**
 * Removes `name =` labels from all named arguments of a call expression, reordering them
 * back to positional order.
 *
 * For example: `foo(x = 1, msg = "hello")` → `foo(1, "hello")`.
 *
 * Applicable when the call has more than one named argument and all named arguments can
 * be safely converted to positional (i.e. they can be reordered to match parameter indices).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaRemoveAllArgumentNamesIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findCallElement(): KtCallElement? =
        PsiTreeUtil.getParentOfType(psi, KtCallElement::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val call = findCallElement() ?: return false
        val args = call.valueArgumentList?.arguments ?: return false
        if (args.count { it.isNamed() } <= 1) return false
        return analyze(kaKtFile) {
            val ctx = RemoveArgumentNamesUtils.collectSortedArgumentsThatCanBeUnnamed(call) ?: return@analyze false
            ctx.sortedArguments.isNotEmpty()
        }
    }

    override fun getDescription(): String = "Remove all argument names"

    override fun implement() {
        val call = findCallElement() ?: return
        var argListStart = 0
        var argListEnd = 0
        var newArgListText: String? = null
        analyze(kaKtFile) {
            val ctx = RemoveArgumentNamesUtils.collectSortedArgumentsThatCanBeUnnamed(call) ?: return
            if (ctx.sortedArguments.isEmpty()) return
            val argList = call.valueArgumentList ?: return
            val newArgTexts = ctx.sortedArguments.flatMap { arg ->
                val isVararg = arg == ctx.vararg
                createArgumentWithoutName(arg, isVararg, isVararg && ctx.varargIsArrayOfCall)
            }
            argListStart = argList.textRange.startOffset
            argListEnd = argList.textRange.endOffset
            newArgListText = "(${newArgTexts.joinToString(", ")})"
        }
        val text = newArgListText ?: return
        doc.atomicChange {
            remove(argListStart, argListEnd - argListStart)
            insertString(argListStart, text, null)
        }
    }
}
