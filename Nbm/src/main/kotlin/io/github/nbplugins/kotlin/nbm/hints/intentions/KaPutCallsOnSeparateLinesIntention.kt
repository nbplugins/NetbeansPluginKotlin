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
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

/**
 * Puts each method call in a chained call expression onto a separate line.
 *
 * For example: `list.filter { it > 0 }.map { it * 2 }` becomes:
 * ```kotlin
 * list.filter { it > 0 }
 *     .map { it * 2 }
 * ```
 *
 * Applicable when the caret is inside a qualified expression chain where at least one `.` or `?.`
 * operator is not preceded by a newline. Does not apply to import directives.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaPutCallsOnSeparateLinesIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findQualifiedExpression(): KtQualifiedExpression? =
        PsiTreeUtil.getParentOfType(psi, KtQualifiedExpression::class.java, false)

    /** Walks up to the outermost KtQualifiedExpression in the chain. */
    private fun KtQualifiedExpression.topmostQualified(): KtQualifiedExpression {
        var current = this
        while (current.parent is KtQualifiedExpression) current = current.parent as KtQualifiedExpression
        return current
    }

    /**
     * Collects the document offsets of `.` / `?.` operation tokens in the chain
     * that are not already preceded by a newline. Skips the innermost operation
     * (mirrors the IDEA default of `WRAP_FIRST_METHOD_IN_CALL_CHAIN = false`).
     */
    private fun collectInsertPositions(root: KtQualifiedExpression): List<Int> {
        // Collect qualified expressions in order from innermost to outermost
        val chain = mutableListOf<KtQualifiedExpression>()
        fun collect(expr: KtQualifiedExpression) {
            val receiver = expr.receiverExpression
            if (receiver is KtQualifiedExpression) collect(receiver)
            chain.add(expr)
        }
        collect(root)
        // Skip the first (innermost) — default WRAP_FIRST_METHOD_IN_CALL_CHAIN = false
        val toWrap = chain.drop(1)
        val positions = mutableListOf<Int>()
        for (qualExpr in toWrap) {
            val opNode = qualExpr.operationTokenNode as? PsiElement ?: continue
            val prevSib = opNode.prevSibling as? PsiWhiteSpace
            if (prevSib == null || !prevSib.textContains('\n')) {
                positions.add(opNode.textRange.startOffset)
            }
        }
        return positions
    }

    override fun isApplicable(caretOffset: Int): Boolean {
        val qualExpr = findQualifiedExpression() ?: return false
        val root = qualExpr.topmostQualified()
        if (root.parent is KtImportDirective) return false
        return collectInsertPositions(root).isNotEmpty()
    }

    override fun getDescription(): String = "Put calls on separate lines"

    override fun implement() {
        val qualExpr = findQualifiedExpression() ?: return
        val root = qualExpr.topmostQualified()
        val positions = collectInsertPositions(root)
        doc.atomicChange {
            for (pos in positions.sortedDescending()) {
                insertString(pos, "\n", null)
            }
        }
    }
}
