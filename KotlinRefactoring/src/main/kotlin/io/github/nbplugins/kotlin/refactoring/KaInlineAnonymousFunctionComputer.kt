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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.k2.refactoring.inline.KotlinInlineAnonymousFunctionProcessor
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Prepared IDEA-engine data for a Kotlin **Inline Anonymous Function** refactoring.
 *
 * @param function lambda or anonymous function selected at the invocation caret
 * @param call immediate invocation accepted by IDEA's anonymous-function processor
 */
data class KaInlineAnonymousFunctionResult(
    val function: KtFunction,
    val call: KtExpression,
)

/**
 * Prepares an immediately invoked lambda or anonymous function for IDEA's K2 inline processor.
 *
 * IDEA remains responsible for recognising supported invocation shapes through
 * [KotlinInlineAnonymousFunctionProcessor.findCallExpression]. The NetBeans adapter owns UI,
 * preview, document persistence, and undo.
 *
 * @param cursorKtFile session-managed Kotlin file containing the invocation caret
 * @param offset caret offset within [cursorKtFile]
 */
class KaInlineAnonymousFunctionComputer(
    private val cursorKtFile: KtFile,
    private val offset: Int,
) {

    /** Result of [compute]. */
    sealed class Outcome {
        /** The lambda/anonymous function and its IDEA-supported immediate call are ready. */
        data class Ready(val result: KaInlineAnonymousFunctionResult) : Outcome()

        /** A lambda/anonymous function was selected but is not immediately invoked. */
        data class Error(val message: String) : Outcome()

        /** The caret does not select a lambda or anonymous function. */
        data object NotApplicable : Outcome()
    }

    /**
     * Finds the lambda or anonymous function enclosing the caret and delegates call validation to IDEA.
     *
     * @return [Outcome.Ready] for an immediately invoked function, [Outcome.Error] for an uninvoked
     *         lambda/anonymous function, or [Outcome.NotApplicable] for all other caret targets
     */
    fun compute(): Outcome {
        val element = cursorKtFile.findElementAt(offset) ?: return Outcome.NotApplicable
        val function = PsiTreeUtil.getParentOfType(element, KtFunction::class.java, false)
            ?.takeIf(::isAnonymousFunction)
            ?: return Outcome.NotApplicable
        val call = KotlinInlineAnonymousFunctionProcessor.findCallExpression(function)
            ?: return Outcome.Error(messageFor(function))
        return Outcome.Ready(KaInlineAnonymousFunctionResult(function, call))
    }

    /** Restricts this route to function literals and unnamed `fun` expressions, not named declarations. */
    private fun isAnonymousFunction(function: KtFunction): Boolean =
        function is KtFunctionLiteral || function is KtNamedFunction && function.nameIdentifier == null

    /** Explains the IDEA eligibility restriction without importing IDEA's modal error UI. */
    private fun messageFor(function: KtFunction): String =
        if (function is KtFunctionLiteral) {
            "Cannot inline this lambda expression because it is not immediately invoked."
        } else {
            "Cannot inline this anonymous function because it is not immediately invoked."
        }
}
