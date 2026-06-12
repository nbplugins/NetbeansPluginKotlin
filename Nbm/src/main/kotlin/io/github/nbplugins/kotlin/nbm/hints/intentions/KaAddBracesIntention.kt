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
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import io.github.nbplugins.kotlin.nbm.reformatting.format
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Adds braces to a single-statement branch of an `if`/`else`, loop, or `when` entry.
 *
 * For example, `if (c) doSomething()` becomes `if (c) { doSomething() }`. For an `if`/`else`
 * with both branches, the target branch is determined by caret position: caret on or after the
 * `else` keyword targets the else branch; otherwise the then branch.
 *
 * Applicable when the nearest enclosing control-flow construct has a branch expression that is
 * not already a [KtBlockExpression].
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddBracesIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    override fun isApplicable(caretOffset: Int): Boolean =
        findTarget(caretOffset) != null

    override fun getDescription(): String = "Add braces"

    override fun implement() {
        val (_, expr) = findTarget(psi.textRange.startOffset) ?: return
        val start = expr.textRange.startOffset
        val end = expr.textRange.endOffset
        val docText = doc.getText(0, doc.length)
        val block = makeBlock(expr.text, start, docText)
        doc.atomicChange {
            remove(start, end - start)
            insertString(start, block, null)
        }
        var lineStart = start
        while (lineStart > 0 && docText[lineStart - 1] != '\n') lineStart--
        format(doc, start, lineStart, start + block.length)
    }

    /**
     * Walks up the PSI tree from [psi] to find the innermost control-flow container whose
     * targeted branch is not yet a block. Returns the container and the branch expression.
     */
    private fun findTarget(caretOffset: Int): Pair<PsiElement, KtExpression>? {
        var current: PsiElement? = psi
        while (current != null && current !is KtFile) {
            when (current) {
                is KtWhenEntry -> {
                    val expr = current.expression
                    if (expr != null && expr !is KtBlockExpression) return current to expr
                }
                is KtLoopExpression -> {
                    val body = current.body
                    if (body != null && body !is KtBlockExpression) return current to body
                }
                is KtIfExpression -> {
                    val expr = targetExpressionForIf(current, caretOffset)
                    if (expr != null && expr !is KtBlockExpression) return current to expr
                }
            }
            current = current.parent
        }
        return null
    }

    /**
     * Determines which branch of [ifExpr] is targeted based on [caretOffset].
     *
     * Returns the else branch when both branches exist and the caret is at or past the `else`
     * keyword; otherwise returns the then branch.
     */
    private fun targetExpressionForIf(ifExpr: KtIfExpression, caretOffset: Int): KtExpression? {
        val thenExpr = ifExpr.then ?: return null
        val elseExpr = ifExpr.`else` ?: return thenExpr
        val elseStart = ifExpr.elseKeyword?.startOffset ?: return thenExpr
        return if (caretOffset >= elseStart) elseExpr else thenExpr
    }
}
