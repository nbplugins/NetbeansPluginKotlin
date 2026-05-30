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
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Removes unnecessary braces from a single-statement block in a control-flow branch.
 *
 * For example, `if (c) { doSomething() }` becomes `if (c) doSomething()`. Applicable when
 * the nearest enclosing block (in an `if`/`else`, loop, or `when` entry) contains exactly one
 * statement that can safely be used without braces.
 *
 * Braces are NOT removed when:
 * - The block contains a declaration (`val`, `var`, class, etc.)
 * - The single statement is a lambda without an arrow (ambiguous syntax)
 * - Removing would produce a dangling-else (nested `if` in a then-branch that has an outer `else`)
 * - The statement is a named declaration inside a `when` entry
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaRemoveBracesIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    override fun isApplicable(caretOffset: Int): Boolean =
        findRemovableBlock() != null

    override fun getDescription(): String = "Remove braces"

    override fun implement() {
        val block = findRemovableBlock() ?: return
        val stmt = block.statements.single()
        val start = block.textRange.startOffset
        val end = block.textRange.endOffset
        doc.atomicChange {
            remove(start, end - start)
            insertString(start, stmt.text, null)
        }
    }

    /**
     * Walks up the PSI tree from [psi] to find the nearest [KtBlockExpression] whose braces
     * can be removed.
     */
    private fun findRemovableBlock(): KtBlockExpression? {
        var current: PsiElement? = psi
        while (current != null && current !is KtFile) {
            if (current is KtBlockExpression) {
                val parent = current.parent
                if ((parent is org.jetbrains.kotlin.psi.KtContainerNode || parent is KtWhenEntry) &&
                    isRemovable(current)
                ) return current
            }
            current = current.parent
        }
        return null
    }

    companion object {
        /**
         * Returns true when [block] contains exactly one statement that can be safely
         * inlined (i.e., braces can be removed).
         *
         * @param block the block to test
         */
        fun isRemovable(block: KtBlockExpression): Boolean {
            val stmt = block.statements.singleOrNull() ?: return false
            if (stmt is KtLambdaExpression && stmt.functionLiteral.arrow == null) return false
            return when (val container = block.parent) {
                is org.jetbrains.kotlin.psi.KtContainerNode -> {
                    if (stmt is KtProperty || stmt is KtClass) return false
                    // Removing braces from `if (outer) { if (inner) x else y }` would cause the
                    // `else y` to bind to `inner` rather than `outer` (dangling-else problem).
                    if (stmt is KtIfExpression) {
                        val outerIf = container.parent as? KtIfExpression
                        if (outerIf?.`else` != null && outerIf.`else` !== block) return false
                    }
                    true
                }
                is KtWhenEntry -> stmt !is KtNamedDeclaration
                else -> false
            }
        }
    }
}
