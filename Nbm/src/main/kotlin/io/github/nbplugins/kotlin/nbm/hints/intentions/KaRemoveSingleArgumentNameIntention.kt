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
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.codeinsight.utils.createArgumentWithoutName
import org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions.RemoveArgumentNamesUtils
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Removes the `name =` label from the single named argument at the caret position.
 *
 * For example, with caret on `b = x`: `foo(a = 1, b = x, c = y)` → `foo(a = 1, x, c = y)`.
 *
 * Applicable when the named argument can be safely unnamed (no positional reordering would
 * be required for preceding arguments).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaRemoveSingleArgumentNameIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findArgument(): KtValueArgument? =
        PsiTreeUtil.getParentOfType(psi, KtValueArgument::class.java, false)
            ?.takeIf { it.isNamed() && it.getArgumentExpression() != null }

    override fun isApplicable(caretOffset: Int): Boolean {
        val arg = findArgument() ?: return false
        if ((arg.parent as? KtValueArgumentList)?.parent !is KtCallElement) return false
        return analyze(kaKtFile) {
            val call = arg.getStrictParentOfType<KtCallElement>() ?: return@analyze false
            val ctx = RemoveArgumentNamesUtils.collectSortedArgumentsThatCanBeUnnamed(call) ?: return@analyze false
            if (arg !in ctx.sortedArguments) return@analyze false
            val allArgs = call.valueArgumentList?.arguments ?: return@analyze false
            val sortedBefore = ctx.sortedArguments.takeWhile { it != arg }
            // name can be removed only when no preceding sorted argument is out of positional order
            !sortedBefore.withIndex().any { (idx, a) -> idx != allArgs.indexOf(a) }
        }
    }

    override fun getDescription(): String = "Remove argument name"

    override fun implement() {
        val arg = findArgument() ?: return
        var newText: String? = null
        val start = arg.textRange.startOffset
        val end = arg.textRange.endOffset
        analyze(kaKtFile) {
            val call = arg.getStrictParentOfType<KtCallElement>() ?: return
            val ctx = RemoveArgumentNamesUtils.collectSortedArgumentsThatCanBeUnnamed(call) ?: return
            val isVararg = arg == ctx.vararg
            newText = createArgumentWithoutName(arg, isVararg, isVararg && ctx.varargIsArrayOfCall).joinToString(", ")
        }
        val text = newText ?: return
        doc.atomicChange {
            remove(start, end - start)
            insertString(start, text, null)
        }
    }
}
