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

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.text.Document
import org.jetbrains.kotlin.idea.codeinsights.impl.base.inspections.MergeIfsUtils.asSingleIfExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Merges two nested `if` statements without an `else` branch into a single `if` with `&&`.
 *
 * For example:
 * ```kotlin
 * if (a) {
 *     if (b) { doSomething() }
 * }
 * ```
 * becomes:
 * ```kotlin
 * if (a && b) { doSomething() }
 * ```
 *
 * Applicable when the caret is on the outer `if` (or its block body), the outer `if` has no
 * `else`, and the then-branch contains exactly one `if` with no `else`.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaMergeIfsIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findIfExpression(): KtIfExpression? {
        return when (val e = PsiTreeUtil.getParentOfType(psi, KtIfExpression::class.java, KtBlockExpression::class.java)) {
            is KtIfExpression -> e
            is KtBlockExpression -> e.parent?.parent as? KtIfExpression
            else -> null
        }
    }

    override fun isApplicable(caretOffset: Int): Boolean {
        val ifExpr = findIfExpression() ?: return false
        if (ifExpr.`else` != null) return false
        val then = ifExpr.then ?: return false
        val nestedIf = then.asSingleIfExpression() ?: return false
        if (nestedIf.`else` != null) return false
        // Caret must not be inside the nested if itself
        return caretOffset !in TextRange(nestedIf.startOffset, nestedIf.endOffset + 1)
    }

    override fun getDescription(): String = "Merge 'if's"

    override fun implement() {
        val ifExpr = findIfExpression() ?: return
        val condition1 = ifExpr.condition ?: return
        val then = ifExpr.then ?: return
        val nestedIf = then.asSingleIfExpression() ?: return
        val condition2 = nestedIf.condition ?: return
        val nestedThen = nestedIf.then ?: return

        // Build the merged if text without PSI mutation.
        val newText = "if (${condition1.text} && ${condition2.text}) ${nestedThen.text}"
        val startOffset = ifExpr.textRange.startOffset
        val endOffset = ifExpr.textRange.endOffset
        doc.atomicChange {
            remove(startOffset, endOffset - startOffset)
            insertString(startOffset, newText, null)
        }
    }
}
