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
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Adds a `name =` label to the single unnamed argument at the caret position.
 *
 * For example: with caret on the last unnamed argument of `foo(1, x)` → `foo(1, b = x)`.
 *
 * Applicable when the caret is on an unnamed (non-lambda) argument that is the last unnamed one
 * in its argument list, and a stable parameter name can be resolved for it.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddNameToArgumentIntention(
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
        // Must be the last unnamed argument (others are covered by AddNamesToCallArguments / AddNamesToFollowingArguments)
        if (argList.arguments.lastOrNull { !it.isNamed() } != arg) return false
        return analyze(kaKtFile) { NamedArgumentUtils.getStableNameFor(arg) != null }
    }

    override fun getDescription(): String {
        val name = analyze(kaKtFile) {
            findArgument()?.let { NamedArgumentUtils.getStableNameFor(it) }
        }
        return if (name != null) "Add '${name.asString()}' to argument" else "Add name to argument"
    }

    override fun implement() {
        val arg = findArgument() ?: return
        var name: Name? = null
        analyze(kaKtFile) { name = NamedArgumentUtils.getStableNameFor(arg) }
        val argName = name ?: return
        val argExpr = arg.getArgumentExpression() ?: return
        val nameStr = argName.asString()
        val newText = if (arg.getSpreadElement() != null) "$nameStr = *${argExpr.text}"
                      else "$nameStr = ${argExpr.text}"
        val start = arg.textRange.startOffset
        val end = arg.textRange.endOffset
        doc.atomicChange {
            remove(start, end - start)
            insertString(start, newText, null)
        }
    }
}
