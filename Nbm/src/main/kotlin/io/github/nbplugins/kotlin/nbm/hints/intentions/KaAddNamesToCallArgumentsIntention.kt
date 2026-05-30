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
import org.jetbrains.kotlin.idea.codeinsight.utils.NamedArgumentUtils
import org.jetbrains.kotlin.idea.codeinsight.utils.dereferenceValidKeys
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile

/**
 * Adds `name =` labels to all unnamed arguments of a function call.
 *
 * For example: `foo(1, "hello")` → `foo(x = 1, msg = "hello")`.
 *
 * Applicable when the caret is on a call expression with two or more unnamed arguments
 * and the function has stable parameter names resolvable by the K2 Analysis API.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddNamesToCallArgumentsIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findCallElement(): KtCallElement? =
        PsiTreeUtil.getParentOfType(psi, KtCallElement::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val call = findCallElement() ?: return false
        val args = call.valueArgumentList?.arguments ?: return false
        if (args.count { !it.isNamed() } < 2) return false
        return analyze(kaKtFile) {
            NamedArgumentUtils.associateArgumentNamesStartingAt(call, null) != null
        }
    }

    override fun getDescription(): String = "Add names to call arguments"

    override fun implement() {
        val call = findCallElement() ?: return
        data class Replacement(val start: Int, val end: Int, val newText: String)
        val replacements = mutableListOf<Replacement>()
        analyze(kaKtFile) {
            val argNames = NamedArgumentUtils.associateArgumentNamesStartingAt(call, null)
                ?.dereferenceValidKeys() ?: return
            for ((arg, name) in argNames) {
                if (arg.isNamed()) continue
                val argExpr = arg.getArgumentExpression() ?: continue
                val nameStr = name.asString()
                val newText = if (arg.getSpreadElement() != null) "$nameStr = *${argExpr.text}"
                              else "$nameStr = ${argExpr.text}"
                replacements.add(Replacement(arg.textRange.startOffset, arg.textRange.endOffset, newText))
            }
        }
        doc.atomicChange {
            for ((start, end, newText) in replacements.sortedByDescending { it.start }) {
                remove(start, end - start)
                insertString(start, newText, null)
            }
        }
    }
}
