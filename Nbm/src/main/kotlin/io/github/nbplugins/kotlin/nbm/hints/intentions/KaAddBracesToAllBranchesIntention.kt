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
import io.github.nbplugins.kotlin.nbm.reformatting.format
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Adds braces to all branches of an `if`/`else` chain or `when` expression at once.
 *
 * For example:
 * ```kotlin
 * if (x > 0) println("pos")
 * else if (x < 0) println("neg")
 * else println("zero")
 * ```
 * becomes:
 * ```kotlin
 * if (x > 0) { println("pos") }
 * else if (x < 0) { println("neg") }
 * else { println("zero") }
 * ```
 *
 * Applicable when the nearest enclosing `if`-chain or `when` has at least 2 total branches
 * and at least one branch is not yet a block. (A single non-block branch is covered by the
 * simpler [KaAddBracesIntention].)
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaAddBracesToAllBranchesIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    override fun isApplicable(caretOffset: Int): Boolean {
        val target = findTarget() ?: return false
        return nonBlockBranches(target).isNotEmpty()
    }

    override fun getDescription(): String = "Add braces to all branches"

    override fun implement() {
        val target = findTarget() ?: return
        val branches = nonBlockBranches(target).sortedByDescending { it.textRange.startOffset }
        val docText = doc.getText(0, doc.length)
        // Compute replacements before atomicChange so PSI offsets stay valid
        val replacements = branches.map { expr ->
            Triple(expr.textRange.startOffset, expr.textRange.endOffset,
                makeBlock(expr.text, expr.textRange.startOffset, docText))
        }
        val rangeStart = target.textRange.startOffset
        val originalRangeEnd = target.textRange.endOffset
        val lenBefore = doc.length
        doc.atomicChange {
            for ((start, end, block) in replacements) {
                remove(start, end - start)
                insertString(start, block, null)
            }
        }
        val newRangeEnd = originalRangeEnd + (doc.length - lenBefore)
        var lineStart = rangeStart
        while (lineStart > 0 && docText[lineStart - 1] != '\n') lineStart--
        format(doc, rangeStart, lineStart, newRangeEnd)
    }

    /**
     * Finds the outermost `if`-chain or innermost `when` expression relative to [psi],
     * whichever is the more specific container.
     */
    private fun findTarget(): KtExpression? {
        val ifExpr = PsiTreeUtil.getParentOfType(psi, KtIfExpression::class.java, false)
        val whenExpr = PsiTreeUtil.getParentOfType(psi, KtWhenExpression::class.java, false)
        val candidate: KtExpression = when {
            ifExpr == null -> whenExpr ?: return null
            whenExpr == null -> ifExpr
            // Pick the innermost (largest startOffset = deepest in the tree)
            ifExpr.textRange.startOffset > whenExpr.textRange.startOffset -> ifExpr
            else -> whenExpr
        }
        return when (candidate) {
            is KtIfExpression -> outermostIf(candidate)
            else -> candidate
        }
    }

    /**
     * Returns branches from [target] that are not already block expressions, only when
     * the target has at least 2 total branches (so "all branches" is meaningful).
     */
    private fun nonBlockBranches(target: KtExpression): List<KtExpression> {
        val all = allBranchExpressions(target)
        if (all.size <= 1) return emptyList()
        return all.filter { it !is KtBlockExpression }
    }
}
