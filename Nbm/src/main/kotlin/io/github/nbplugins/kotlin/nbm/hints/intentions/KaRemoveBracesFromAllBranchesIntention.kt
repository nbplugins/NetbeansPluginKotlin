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
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Removes braces from all branches of an `if`/`else` chain or `when` expression at once.
 *
 * For example:
 * ```kotlin
 * if (x > 0) { println("pos") }
 * else if (x < 0) { println("neg") }
 * else { println("zero") }
 * ```
 * becomes:
 * ```kotlin
 * if (x > 0) println("pos")
 * else if (x < 0) println("neg")
 * else println("zero")
 * ```
 *
 * Applicable when the nearest enclosing `if`-chain or `when` has at least 2 total branches,
 * all of which are block expressions whose braces can be safely removed (see
 * [KaRemoveBracesIntention.isRemovable] for the removal conditions).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaRemoveBracesFromAllBranchesIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    override fun isApplicable(caretOffset: Int): Boolean {
        val target = findTarget() ?: return false
        val blocks = removableBlockBranches(target)
        return blocks.isNotEmpty()
    }

    override fun getDescription(): String = "Remove braces from all branches"

    override fun implement() {
        val target = findTarget() ?: return
        val blocks = removableBlockBranches(target).sortedByDescending { it.textRange.startOffset }
        doc.atomicChange {
            for (block in blocks) {
                val stmt = block.statements.singleOrNull() ?: continue
                val start = block.textRange.startOffset
                val end = block.textRange.endOffset
                remove(start, end - start)
                insertString(start, stmt.text, null)
            }
        }
    }

    /**
     * Finds the outermost `if`-chain or innermost `when` expression relative to [psi].
     */
    private fun findTarget(): KtExpression? {
        val ifExpr = PsiTreeUtil.getParentOfType(psi, KtIfExpression::class.java, false)
        val whenExpr = PsiTreeUtil.getParentOfType(psi, KtWhenExpression::class.java, false)
        val candidate: KtExpression = when {
            ifExpr == null -> whenExpr ?: return null
            whenExpr == null -> ifExpr
            ifExpr.textRange.startOffset > whenExpr.textRange.startOffset -> ifExpr
            else -> whenExpr
        }
        return when (candidate) {
            is KtIfExpression -> outermostIf(candidate)
            else -> candidate
        }
    }

    /**
     * Returns block branches from [target] that all pass [KaRemoveBracesIntention.isRemovable],
     * only when there are at least 2 total branches and ALL of them are removable blocks.
     *
     * Returns empty list if any branch cannot have its braces removed.
     */
    private fun removableBlockBranches(target: KtExpression): List<KtBlockExpression> {
        val all = allBranchExpressions(target)
        if (all.size <= 1) return emptyList()
        val blocks = all.filterIsInstance<KtBlockExpression>()
        if (blocks.size != all.size) return emptyList()  // some branches are not blocks
        if (blocks.any { !KaRemoveBracesIntention.isRemovable(it) }) return emptyList()
        return blocks
    }
}
