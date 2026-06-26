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

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.k2.refactoring.inline.createUsageReplacementStrategyForFunction
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Result of [KaInlineFunctionComputer]: everything the NetBeans [io.github.nbplugins.kotlin.nbm.refactoring.KotlinInlineFunctionPlugin]
 * needs to apply an inline-function refactoring through the IDEA `codeInliner/` engine.
 *
 * @param function         the [KtNamedFunction] selected for inlining (deleted after all usages are inlined)
 * @param strategy         the IDEA-produced `UsageReplacementStrategy` whose `createReplacer` is invoked per usage
 * @param usages           every [KtReferenceExpression] across the project that resolves to [function], grouped per [KtFile]
 * @param declarationName  the function's simple name (for the dialog title / preview)
 */
data class KaInlineFunctionResult(
    val function: KtNamedFunction,
    val strategy: org.jetbrains.kotlin.idea.refactoring.inline.codeInliner.UsageReplacementStrategy,
    val usages: Map<KtFile, List<KtReferenceExpression>>,
    val declarationName: String,
)

/**
 * Validation error returned by [KaInlineFunctionComputer] when the cursor is on a function that
 * cannot be inlined (no body, abstract, external, etc.).
 *
 * @param message          human-readable reason
 * @param declarationName  function name (may be empty) — for the dialog title
 */
data class KaInlineFunctionError(
    val message: String,
    val declarationName: String,
)

/**
 * Prepares an inline-function refactoring by driving IDEA's K2 `codeInliner/` engine without
 * starting the IDEA `BaseRefactoringProcessor` pipeline.
 *
 * Steps performed by [compute]:
 *  1. Locate the [KtNamedFunction] at the cursor (reference-resolve first, then parent-walk fallback).
 *  2. Validate: the function must have a body expression and must not be abstract or external.
 *  3. Call IDEA's top-level `createUsageReplacementStrategyForFunction(...)` to build the
 *     `CallableUsageReplacementStrategy` that knows how to inline the function body at any call site.
 *  4. Scan every [KtFile] in [projectKtFiles] for [KtReferenceExpression]s that resolve to the function.
 *
 * The actual PSI mutation is performed by the NetBeans plugin layer in a document write transaction.
 *
 * @param cursorKtFile    K2-session [KtFile] containing the cursor
 * @param offset          character offset of the cursor within [cursorKtFile]
 * @param project         the IDEA [Project] of the analysis session
 * @param projectKtFiles  every [KtFile] registered in the analysis session (for usage scan)
 */
class KaInlineFunctionComputer(
    private val cursorKtFile: KtFile,
    private val offset: Int,
    private val project: Project,
    private val projectKtFiles: Collection<KtFile>,
) {

    companion object {
        /**
         * Post-processes inlined source text to simplify `${"literal"}` entries produced by
         * IDEA's `CodeInliner` when a string-typed argument is substituted into a string template.
         *
         * Example: `"Hello, ${"World"}!"` → `"Hello, World!"`.
         *
         * Only the safe case is simplified: the inner string must contain no `"`, `$`, or `\`
         * characters (no escapes, no nested templates, no ambiguous boundaries).
         *
         * @param text Kotlin source text after `CallableUsageReplacementStrategy` has been applied
         * @return text with plain-literal string template captures collapsed to their content
         */
        fun simplifyStringTemplateCaptures(text: String): String {
            // Pattern: ${"<content>"} where content has no ", $, or \
            val pattern = Regex("\\$\\{\"([^\"\\$\\\\]*)\"\\}")
            return text.replace(pattern, "\$1")
        }
    }

    /** Result of [compute]. */
    sealed class Outcome {
        /** Refactoring can proceed. */
        data class Ready(val result: KaInlineFunctionResult) : Outcome()
        /** Cursor is on a function but it cannot be inlined; surface [error] to the user. */
        data class Error(val error: KaInlineFunctionError) : Outcome()
        /** Cursor is not on a [KtNamedFunction] — the inline action is not applicable. */
        data object NotApplicable : Outcome()
    }

    /**
     * Resolves the function at the cursor and builds the inline plan.
     *
     * The cursor may be on the **declaration** (`fun <caret>foo()`) or on any **call site**
     * (`<caret>foo()`). Both are handled:
     *  1. If the leaf element is inside a [KtNameReferenceExpression], resolve via K2 and obtain
     *     the declaring [KtNamedFunction] from the symbol's PSI.
     *  2. Otherwise walk up the PSI tree looking for a [KtNamedFunction] ancestor (handles the
     *     caret being on the `fun` keyword or function name itself).
     *
     * @return [Outcome.NotApplicable] when the cursor is not on a function,
     *         [Outcome.Error] when the function cannot be inlined,
     *         [Outcome.Ready] with the prepared plan otherwise
     */
    fun compute(): Outcome {
        val element = cursorKtFile.findElementAt(offset) ?: return Outcome.NotApplicable
        val function = resolveFunctionFromReference(element)
            ?: PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)
            ?: return Outcome.NotApplicable
        val declarationName = function.name ?: return Outcome.NotApplicable

        // Validate: must have a body (not abstract, not external, not a function type without body)
        if (function.bodyExpression == null && !function.hasBlockBody()) {
            return Outcome.Error(
                KaInlineFunctionError(
                    "Cannot inline '$declarationName': the function has no body.",
                    declarationName,
                )
            )
        }
        if (function.hasModifier(KtTokens.ABSTRACT_KEYWORD)) {
            return Outcome.Error(
                KaInlineFunctionError(
                    "Cannot inline '$declarationName': abstract functions cannot be inlined.",
                    declarationName,
                )
            )
        }
        if (function.hasModifier(KtTokens.EXTERNAL_KEYWORD)) {
            return Outcome.Error(
                KaInlineFunctionError(
                    "Cannot inline '$declarationName': external functions cannot be inlined.",
                    declarationName,
                )
            )
        }

        val strategy = runCatching {
            createUsageReplacementStrategyForFunction(function, editor = null, fallbackToSuperCall = false)
        }.getOrNull() ?: return Outcome.Error(
            KaInlineFunctionError(
                "Cannot inline '$declarationName': failed to build the inline replacement code.",
                declarationName,
            )
        )
        if (strategy == null) {
            return Outcome.Error(
                KaInlineFunctionError(
                    "Cannot inline '$declarationName': failed to build the inline replacement code.",
                    declarationName,
                )
            )
        }

        val usages = findUsages(function)
        return Outcome.Ready(
            KaInlineFunctionResult(
                function = function,
                strategy = strategy,
                usages = usages,
                declarationName = declarationName,
            )
        )
    }

    /**
     * If [element] is inside a [KtNameReferenceExpression], resolves it via K2 and returns
     * the [KtNamedFunction] the reference points to. Returns `null` when not a function reference.
     *
     * This enables the refactoring to work when the cursor is on a **call site** rather than
     * on the declaration.
     */
    private fun resolveFunctionFromReference(element: PsiElement): KtNamedFunction? {
        val ref = PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
            ?: return null
        return runCatching {
            analyze(ref) {
                ref.mainReference?.resolveToSymbol()?.psi as? KtNamedFunction
            }
        }.getOrNull()
    }

    /**
     * Scans every [KtFile] in [projectKtFiles] and collects every [KtReferenceExpression] whose
     * `mainReference` resolves to [function], grouped per containing [KtFile].
     */
    private fun findUsages(function: KtNamedFunction): Map<KtFile, List<KtReferenceExpression>> {
        val target: PsiElement = function
        val byFile = LinkedHashMap<KtFile, MutableList<KtReferenceExpression>>()
        for (file in projectKtFiles) {
            val refs = mutableListOf<KtReferenceExpression>()
            runCatching {
                analyze(file) {
                    file.accept(object : KtTreeVisitorVoid() {
                        override fun visitReferenceExpression(expression: KtReferenceExpression) {
                            super.visitReferenceExpression(expression)
                            val sym = runCatching {
                                expression.mainReference?.resolveToSymbol()
                            }.getOrNull() ?: return
                            if (sym.psi == target) refs.add(expression)
                        }
                    })
                }
            }
            if (refs.isNotEmpty()) byFile[file] = refs
        }
        return byFile
    }
}
