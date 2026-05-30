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
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Adds `name =` labels to the argument at the caret position and all following unnamed arguments.
 *
 * For example, with caret on the second argument of `foo(1, x, y)`:
 * `foo(1, x, y)` → `foo(1, b = x, c = y)`.
 *
 * Applicable when the caret is on an unnamed argument (not the first and not the last unnamed
 * one), and stable parameter names can be resolved for this call.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddNamesToFollowingArgumentsIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findArgument(): KtValueArgument? =
        PsiTreeUtil.getParentOfType(psi, KtValueArgument::class.java, false)
            ?.takeIf { it !is KtLambdaArgument }

    override fun isApplicable(caretOffset: Int): Boolean {
        val arg = findArgument() ?: return false
        if (arg.isNamed()) return false
        val argList = arg.parent as? KtValueArgumentList ?: return false
        if (argList.arguments.firstOrNull() == arg) return false   // shadowed by AddNamesToCallArguments
        if (argList.arguments.lastOrNull { !it.isNamed() } == arg) return false  // shadowed by AddNameToArgument
        val call = argList.parent as? KtCallElement ?: return false
        return analyze(kaKtFile) {
            NamedArgumentUtils.associateArgumentNamesStartingAt(call, arg) != null
        }
    }

    override fun getDescription(): String = "Add names to this argument and following arguments"

    override fun implement() {
        val arg = findArgument() ?: return
        val argList = arg.parent as? KtValueArgumentList ?: return
        val call = argList.parent as? KtCallElement ?: return
        data class Replacement(val start: Int, val end: Int, val newText: String)
        val replacements = mutableListOf<Replacement>()
        analyze(kaKtFile) {
            val argNames = NamedArgumentUtils.associateArgumentNamesStartingAt(call, arg)
                ?.dereferenceValidKeys() ?: return
            for ((a, name) in argNames) {
                if (a.isNamed()) continue
                val argExpr = a.getArgumentExpression() ?: continue
                val nameStr = name.asString()
                val newText = if (a.getSpreadElement() != null) "$nameStr = *${argExpr.text}"
                              else "$nameStr = ${argExpr.text}"
                replacements.add(Replacement(a.textRange.startOffset, a.textRange.endOffset, newText))
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
