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
import org.jetbrains.kotlin.diagnostics.WhenMissingCase
import org.jetbrains.kotlin.idea.codeinsights.impl.base.intentions.AddRemainingWhenBranchesUtils
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtWhenExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Adds all missing branches to a `when` expression using the K2 Analysis API.
 *
 * Applicable when the caret is on a `when` expression whose subject is a sealed class or enum
 * and at least one non-unknown branch is missing. Generates `BranchName -> TODO()` stubs for
 * every missing case and inserts them before the closing brace of the `when`.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddWhenRemainingBranchesIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findWhenExpr(): KtWhenExpression? =
        PsiTreeUtil.getParentOfType(psi, KtWhenExpression::class.java, false)

    private fun missingCases(whenExpr: KtWhenExpression): List<WhenMissingCase>? =
        analyze(kaKtFile) {
            whenExpr.computeMissingCases().takeIf {
                it.isNotEmpty() && it.singleOrNull() != WhenMissingCase.Unknown
            }
        }

    override fun isApplicable(caretOffset: Int): Boolean {
        val whenExpr = findWhenExpr() ?: return false
        return missingCases(whenExpr) != null
    }

    override fun getDescription(): String = AddRemainingWhenBranchesUtils.familyAndActionName(false)

    override fun implement() {
        val whenExpr = findWhenExpr() ?: return
        val cases = missingCases(whenExpr) ?: return

        val closeBrace = whenExpr.closeBrace ?: return
        val elseBranch = whenExpr.entries.find { it.isElse }
        val insertOffset = (elseBranch ?: closeBrace).textRange.startOffset

        val insertText = cases.joinToString("") { case ->
            "    ${branchConditionText(case)} -> TODO()\n"
        }
        doc.atomicChange {
            insertString(insertOffset, insertText, null)
        }
    }

    private fun branchConditionText(case: WhenMissingCase): String = when (case) {
        WhenMissingCase.Unknown,
        WhenMissingCase.NullIsMissing,
        is WhenMissingCase.BooleanIsMissing,
        is WhenMissingCase.ConditionTypeIsExpect -> case.branchConditionText
        is WhenMissingCase.IsTypeCheckIsMissing ->
            (if (case.isSingleton) "" else "is ") + case.classId.relativeClassName.asString()
        is WhenMissingCase.EnumCheckIsMissing -> {
            val prefix = case.callableId.className?.asString() ?: ""
            if (prefix.isEmpty()) case.callableId.callableName.asString()
            else "$prefix.${case.callableId.callableName.asString()}"
        }
    }
}
