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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousFunctionSymbol
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.quoteIfNeeded
import org.jetbrains.kotlin.types.Variance

/**
 * Converts a lambda expression to an anonymous function.
 *
 * For example:
 * ```kotlin
 * list.filter { x -> x > 0 }
 * ```
 * becomes:
 * ```kotlin
 * list.filter(fun(x: Int): Boolean { return x > 0 })
 * ```
 *
 * Not applicable when:
 * - The lambda has destructuring declarations in parameters.
 * - The lambda is inside an `inline` function call.
 * - The lambda is a suspend function literal.
 * - Any parameter type cannot be resolved (error type).
 *
 * When the lambda is a trailing argument, the resulting anonymous function is moved
 * inside the parentheses by rewriting the whole call expression as text.
 *
 * The function text is built entirely from rendered type strings to avoid PSI tree copy
 * operations that are unavailable in standalone (non-IDE) mode.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaLambdaToAnonymousFunctionIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    /** Finds the enclosing lambda expression. */
    private fun findLambdaExpr(): KtLambdaExpression? =
        PsiTreeUtil.getParentOfType(psi, KtLambdaExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val lambda = findLambdaExpr() ?: return false

        // No destructuring in parameters
        if (lambda.functionLiteral.valueParameters.any { it.destructuringDeclaration != null }) return false

        // Not inside an inline function call
        val argument = lambda.getStrictParentOfType<KtValueArgument>()
        val call = argument?.getStrictParentOfType<KtCallExpression>()
        if (call?.getStrictParentOfType<KtFunction>()?.hasModifier(KtTokens.INLINE_KEYWORD) == true) return false

        return analyze(kaKtFile) {
            val symbol = lambda.functionLiteral.symbol as? KaAnonymousFunctionSymbol ?: return@analyze false
            if (symbol.valueParameters.any { it.returnType is KaErrorType }) return@analyze false
            // No suspend anonymous functions in Kotlin
            if ((lambda.functionLiteral.expressionType as? KaFunctionType)?.isSuspend == true) return@analyze false
            buildFunctionText(lambda) != null
        }
    }

    override fun getDescription(): String = "Convert to anonymous function"

    override fun implement() {
        val lambda = findLambdaExpr() ?: return
        val functionText = analyze(kaKtFile) { buildFunctionText(lambda) } ?: return

        val lambdaArg = lambda.parent as? KtLambdaArgument
        if (lambdaArg != null) {
            // Trailing lambda: rewrite "call { ... }" → "call(fun(...) { ... })"
            val callExpr = lambdaArg.parent as? KtCallExpression ?: return
            val nonLambdaArgs = callExpr.valueArguments.filter { it !is KtLambdaArgument }
            val calleeText = callExpr.calleeExpression?.text ?: return
            val typeArgs = callExpr.typeArgumentList?.text ?: ""
            val argsText = nonLambdaArgs.joinToString(", ") { it.text }
            val newCallText = if (argsText.isEmpty()) {
                "$calleeText$typeArgs($functionText)"
            } else {
                "$calleeText$typeArgs($argsText, $functionText)"
            }
            val start = callExpr.textRange.startOffset
            val len = callExpr.textRange.length
            doc.atomicChange {
                remove(start, len)
                insertString(start, newCallText, null)
            }
        } else {
            val start = lambda.textRange.startOffset
            val len = lambda.textRange.length
            doc.atomicChange {
                remove(start, len)
                insertString(start, functionText, null)
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Builds the anonymous-function text from the lambda's K2 symbol, entirely using
     * rendered type strings. Avoids any PSI tree copy / replace operations.
     *
     * @param lambda the lambda expression to convert
     * @return the `fun(params): ReturnType { body }` string, or null if inapplicable.
     */
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildFunctionText(
        lambda: KtLambdaExpression
    ): String? {
        val functionLiteral = lambda.functionLiteral
        val body = functionLiteral.bodyBlockExpression ?: return null
        val symbol = functionLiteral.symbol as? KaAnonymousFunctionSymbol ?: return null

        // Build parameter list: "fun(name: Type, ...)"
        val params = symbol.valueParameters.joinToString(", ") { param ->
            val typeStr = param.returnType.render(position = Variance.IN_VARIANCE)
            val nameStr = if (param.name.isSpecial) "_" else param.name.asString().quoteIfNeeded()
            "$nameStr: $typeStr"
        }

        // Build return type annotation (omit if Unit or error)
        val returnType = symbol.returnType
        val omitReturnType = returnType.isUnitType || returnType is KaErrorType
        val returnTypeStr = if (omitReturnType) "" else ": ${returnType.render(position = Variance.OUT_VARIANCE)}"

        // Build body text with explicit return on the last statement if needed
        val bodyText = buildBodyText(body.statements, needsExplicitReturn = !omitReturnType)

        return "fun($params)$returnTypeStr { $bodyText }"
    }

    /**
     * Builds the body text for the anonymous function.
     *
     * If [needsExplicitReturn] is true and the last statement is not already a return
     * expression, the last statement gets a `return ` prefix.
     *
     * @param statements the list of statements in the lambda body
     * @param needsExplicitReturn whether the last statement needs a `return` prefix
     * @return the body text as a single-line string with `;`-separated statements
     */
    private fun buildBodyText(statements: List<KtExpression>, needsExplicitReturn: Boolean): String {
        if (statements.isEmpty()) return ""
        val stmtTexts = statements.mapIndexed { idx, stmt ->
            val text = stmt.text
            if (needsExplicitReturn && idx == statements.lastIndex && stmt !is KtReturnExpression) {
                "return $text"
            } else {
                text
            }
        }
        return stmtTexts.joinToString("; ")
    }

}
