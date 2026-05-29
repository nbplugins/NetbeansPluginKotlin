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
import org.jetbrains.kotlin.idea.base.psi.isInsideAnnotationEntryArgumentList
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange

/**
 * Converts a string concatenation expression to a `buildString { }` call.
 *
 * Applicable when the caret is on (or inside) a string-plus binary expression chain and the
 * outermost expression in that chain has a `String` type (i.e., the full concatenation
 * produces a String) and is not inside an annotation argument list.
 *
 * For example:
 * ```kotlin
 * "Hello " + name + "!"
 * ```
 * becomes:
 * ```kotlin
 * buildString {
 *     append("Hello ")
 *     append(name)
 *     append("!")
 * }
 * ```
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertConcatenationToBuildStringIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    /**
     * Finds the outermost `+` binary expression that contains the caret element,
     * walking up through any parent `+` expressions to reach the full chain root.
     */
    private fun findBinaryExpr(): KtBinaryExpression? {
        var expr = PsiTreeUtil.getParentOfType(psi, KtBinaryExpression::class.java, false) ?: return null
        if (expr.operationToken != KtTokens.PLUS) return null
        while (true) {
            val parent = expr.parent as? KtBinaryExpression ?: break
            if (parent.operationToken != KtTokens.PLUS) break
            expr = parent
        }
        return expr
    }

    override fun isApplicable(caretOffset: Int): Boolean {
        val expr = findBinaryExpr() ?: return false
        if (expr.isInsideAnnotationEntryArgumentList()) return false
        return analyze(kaKtFile) { expr.expressionType?.isStringType == true }
    }

    override fun getDescription(): String = "Convert concatenation to 'buildString'"

    override fun implement() {
        val expr = findBinaryExpr() ?: return
        val operands = collectBinaryOperands(expr)
        val sb = StringBuilder("buildString {\n")
        for (operand in operands) {
            // Unpack single-expression templates like "${name}" or "$name" → append(name)
            val appendArg = when {
                operand is KtStringTemplateExpression &&
                    operand.entries.size == 1 &&
                    operand.entries[0] is KtStringTemplateEntryWithExpression ->
                    (operand.entries[0] as KtStringTemplateEntryWithExpression).expression?.text ?: operand.text
                else -> operand.text
            }
            sb.append("append($appendArg)\n")
        }
        sb.append("}")
        val start = expr.textRange.startOffset
        doc.atomicChange {
            remove(start, expr.textRange.length)
            insertString(start, sb.toString(), null)
        }
    }
}
