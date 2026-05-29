/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.idea.base.psi.getLineNumber
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.psiUtil.allChildren

/**
 * Converts a single-line lambda body to multi-line format by expanding each statement
 * onto its own line.
 *
 * For example:
 * ```kotlin
 * { x -> a; b; c }
 * ```
 * becomes:
 * ```kotlin
 * { x ->
 * a
 * b
 * c
 * }
 * ```
 *
 * Applicable only when the lambda is currently on a single line.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertLambdaToMultiLineIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    /** Finds the enclosing lambda expression. */
    private fun findLambdaExpr(): KtLambdaExpression? =
        PsiTreeUtil.getParentOfType(psi, KtLambdaExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val lambda = findLambdaExpr() ?: return false
        val literal = lambda.functionLiteral
        if (literal.bodyBlockExpression == null) return false
        val startLine = literal.getLineNumber(start = true)
        val endLine = literal.getLineNumber(start = false)
        return startLine == endLine
    }

    override fun getDescription(): String = "Put lambda body on multiple lines"

    override fun implement() {
        val lambda = findLambdaExpr() ?: return
        val newText = buildMultiLineText(lambda) ?: return
        val start = lambda.textRange.startOffset
        val len = lambda.textRange.length
        doc.atomicChange {
            remove(start, len)
            insertString(start, newText, null)
        }
    }

    /**
     * Builds a multi-line representation of [lambda].
     * Semicolons between statements are converted to newlines.
     *
     * @param lambda the lambda expression to expand
     * @return the multi-line text, or null if the body cannot be determined
     */
    private fun buildMultiLineText(lambda: KtLambdaExpression): String? {
        val literal = lambda.functionLiteral
        val body = literal.bodyBlockExpression ?: return null
        val params = literal.valueParameters.joinToString(", ") { it.text }
        val arrow = if (params.isNotEmpty()) " $params ->" else ""
        val statements = body.statements
        if (statements.isEmpty()) return "{$arrow\n}"
        val stmtsText = statements.joinToString("\n") { it.text }
        return "{\n$arrow\n$stmtsText\n}"
    }
}

/**
 * Converts a multi-line lambda body to a single-line format.
 *
 * For example:
 * ```kotlin
 * { x ->
 *     a
 *     b
 * }
 * ```
 * becomes:
 * ```kotlin
 * { x -> a; b }
 * ```
 *
 * Not applicable when the lambda body contains end-of-line comments (`// ...`), because
 * collapsing to a single line would change the semantics of those comments.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertLambdaToSingleLineIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    /** Finds the enclosing lambda expression. */
    private fun findLambdaExpr(): KtLambdaExpression? =
        PsiTreeUtil.getParentOfType(psi, KtLambdaExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val lambda = findLambdaExpr() ?: return false
        val literal = lambda.functionLiteral
        val body = literal.bodyBlockExpression ?: return false
        val startLine = literal.getLineNumber(start = true)
        val endLine = literal.getLineNumber(start = false)
        if (startLine == endLine) return false
        // Cannot collapse if body contains end-of-line comments
        return body.allChildren.none { child ->
            child is PsiComment && child.node.elementType == KtTokens.EOL_COMMENT
        }
    }

    override fun getDescription(): String = "Put lambda body on one line"

    override fun implement() {
        val lambda = findLambdaExpr() ?: return
        val newText = buildSingleLineText(lambda) ?: return
        val start = lambda.textRange.startOffset
        val len = lambda.textRange.length
        doc.atomicChange {
            remove(start, len)
            insertString(start, newText, null)
        }
    }

    /**
     * Builds a single-line representation of [lambda].
     * Multiple statements are joined with semicolons.
     *
     * @param lambda the lambda expression to collapse
     * @return the single-line text, or null if the body cannot be determined
     */
    private fun buildSingleLineText(lambda: KtLambdaExpression): String? {
        val literal = lambda.functionLiteral
        val body = literal.bodyBlockExpression ?: return null
        val params = literal.valueParameters.joinToString(", ") { it.text }
        val arrow = if (params.isNotEmpty()) " $params ->" else ""
        val statements = body.statements
        if (statements.isEmpty()) return "{$arrow }"
        val stmtsText = statements.joinToString("; ") { it.text }
        return "{$arrow $stmtsText }"
    }
}
