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
import org.jetbrains.kotlin.idea.k2.refactoring.inline.codeInliner.CodeToInlineBuilder
import org.jetbrains.kotlin.idea.k2.refactoring.inline.codeInliner.PropertyUsageReplacementStrategy
import org.jetbrains.kotlin.idea.refactoring.inline.AbstractKotlinInlinePropertyProcessor
import org.jetbrains.kotlin.idea.refactoring.inline.codeInliner.UsageReplacementStrategy
import org.jetbrains.kotlin.idea.refactoring.inline.createReplacementStrategyForProperty
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Result of [KaInlineVariableComputer]: everything the NetBeans `KotlinInlineVariablePlugin`
 * needs to apply an inline-variable refactoring through the IDEA `codeInliner/` engine.
 *
 * @param property         the `KtProperty` selected for inlining (used by the apply step to delete the declaration)
 * @param strategy         the IDEA-produced `UsageReplacementStrategy` whose [UsageReplacementStrategy.createReplacer]
 *                         is invoked for each usage to inline the initializer in place
 * @param usages           every `KtReferenceExpression` across the project that resolves to [property], grouped per
 *                         containing `KtFile` (each containing `KtFile` is a session-managed instance)
 * @param declarationName  the property's simple name (for display purposes — preview / dialog title)
 */
data class KaInlineVariableResult(
    val property: KtProperty,
    val strategy: UsageReplacementStrategy,
    val usages: Map<KtFile, List<KtReferenceExpression>>,
    val declarationName: String,
)

/**
 * Validation error returned by [KaInlineVariableComputer] when the cursor is on a `val`/`var`
 * that cannot be inlined (missing initializer, write-usages on a `val` without initializer in
 * declaration, etc.). The message text comes from IDEA's `RefactoringBundle`.
 *
 * @param message       human-readable reason the refactoring cannot proceed (verbatim from IDEA)
 * @param declarationName  property name (or empty) — for preview / dialog title
 */
data class KaInlineVariableError(
    val message: String,
    val declarationName: String,
)

/**
 * Prepares an inline-variable refactoring by driving IDEA's K2 `codeInliner/` engine without
 * starting the IDEA `BaseRefactoringProcessor` pipeline.
 *
 * Steps performed by [compute]:
 *  1. Locate the [KtProperty] at the cursor (parent walk from `findElementAt(offset)`).
 *  2. Run IDEA's `AbstractKotlinInlinePropertyProcessor.extractInitialization` — returns either
 *     the initializer expression or an error message (e.g. "property has no initializer").
 *  3. Run IDEA's top-level `createReplacementStrategyForProperty(...)` — this builds the
 *     `PropertyUsageReplacementStrategy` (which wraps a `CodeInliner`) that knows how to inline
 *     the initializer at any reference site, with proper handling of parentheses, qualified calls,
 *     imports and type arguments.
 *  4. Scan every `KtFile` in [projectKtFiles] for `KtReferenceExpression`s whose `mainReference`
 *     resolves to [property] — these are the usages to inline.
 *
 * The actual PSI mutation (`strategy.createReplacer(usage).invoke()` + `property.delete()`) is
 * performed by the NetBeans plugin layer, inside a document write transaction. Keeping it there
 * lets NetBeans control undo granularity.
 *
 * @param cursorKtFile    K2-session `KtFile` containing the cursor
 * @param offset          character offset of the cursor within [cursorKtFile]
 * @param project         the IDEA `Project` of the analysis session (used by IDEA's
 *                        `extractInitialization` and `createReplacementStrategyForProperty`)
 * @param projectKtFiles  every `KtFile` registered in the analysis session — used to find usages
 */
class KaInlineVariableComputer(
    private val cursorKtFile: KtFile,
    private val offset: Int,
    private val project: Project,
    private val projectKtFiles: Collection<KtFile>,
) {

    /**
     * Result of [compute] — either the prepared refactoring data, an error, or `null` when no
     * `KtProperty` is at the cursor (the action should be silently unavailable).
     */
    sealed class Outcome {
        /** Refactoring can proceed. */
        data class Ready(val result: KaInlineVariableResult) : Outcome()
        /** Cursor is on a property but it cannot be inlined; show [error] to the user. */
        data class Error(val error: KaInlineVariableError) : Outcome()
        /** Cursor is not on a `KtProperty` — the inline-variable action is not applicable. */
        data object NotApplicable : Outcome()
    }

    /**
     * Resolves the property at the cursor and builds the inline plan.
     *
     * The cursor may be either on the **declaration** (`val <caret>x = 42`) or on any **usage**
     * (`println(<caret>x)`). Both are handled:
     *  1. If the leaf element is (or is inside) a [KtNameReferenceExpression], resolve its symbol
     *     via K2 and obtain the declaring [KtProperty] from the symbol's PSI.
     *  2. Otherwise fall back to walking up the PSI tree looking for a [KtProperty] ancestor
     *     (handles the cursor being on the declaration keyword/name itself).
     *
     * @return [Outcome.NotApplicable] when the cursor is not on a `KtProperty`,
     *         [Outcome.Error] when the property cannot be inlined (missing initializer, etc.),
     *         [Outcome.Ready] with the prepared plan otherwise
     */
    fun compute(): Outcome {
        val element = cursorKtFile.findElementAt(offset) ?: return Outcome.NotApplicable
        val property = resolvePropertyFromReference(element)
            ?: PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
            ?: return Outcome.NotApplicable
        val declarationName = property.name ?: return Outcome.NotApplicable

        // IDEA's extractInitialization validates the property and returns either the initializer
        // expression or an error (e.g. no initializer, multiple assignments without a dominator).
        val init = AbstractKotlinInlinePropertyProcessor.extractInitialization(property)
        val initializer = init.initializerOrNull
        if (initializer == null) {
            // We don't pass a non-null Editor — the only effect would be IDEA's `showErrorHint`
            // popup, which makes no sense in headless NB context. The error message itself is
            // built by `createInitializationWithError` and is accessible only through
            // `getInitializerOrShowErrorHint`. Replicate the message lookup:
            val msg = errorMessageFor(property)
            return Outcome.Error(KaInlineVariableError(msg, declarationName))
        }

        // Build the replacement strategy via IDEA's top-level helper. We do not call the
        // IDEA error hint, so `editor = null` is fine.
        val strategy = createReplacementStrategyForProperty(
            property = property,
            editor = null,
            project = project,
            createBuilder = { decl, fallbackToSuperCall ->
                CodeToInlineBuilder(original = decl, fallbackToSuperCall = fallbackToSuperCall)
            },
            createStrategy = { readReplacement, writeReplacement ->
                PropertyUsageReplacementStrategy(readReplacement, writeReplacement)
            },
        ) ?: run {
            // strategy build returned null without a richer error — fall back to a generic message
            return Outcome.Error(
                KaInlineVariableError(
                    "Cannot inline '$declarationName': failed to build the inline replacement code.",
                    declarationName,
                )
            )
        }

        val usages = findUsages(property)
        return Outcome.Ready(
            KaInlineVariableResult(
                property = property,
                strategy = strategy,
                usages = usages,
                declarationName = declarationName,
            )
        )
    }

    /**
     * If [element] is (or is inside) a [KtNameReferenceExpression], resolves it via K2 and
     * returns the [KtProperty] that the reference points to. Returns `null` when [element] is
     * not a reference or the resolved symbol is not a local/member property.
     *
     * This enables the refactoring to work when the cursor is on a **usage** of the variable
     * rather than on its declaration.
     */
    private fun resolvePropertyFromReference(element: PsiElement): KtProperty? {
        val ref = PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
            ?: return null
        return runCatching {
            analyze(ref) {
                ref.mainReference?.resolveToSymbol()?.psi as? KtProperty
            }
        }.getOrNull()
    }

    /**
     * Re-derives the error message IDEA would have shown when [property] has no inlineable
     * initializer. Mirrors `AbstractKotlinInlinePropertyProcessor.createInitializationWithError`
     * but doesn't depend on `RefactoringBundle` reaching a localised resource — when the bundle
     * cannot resolve a key (common in our standalone setup), a generic English message is used.
     */
    private fun errorMessageFor(property: KtProperty): String {
        val name = property.name ?: return "Cannot inline property: no initializer"
        return "Cannot inline '$name': the property has no inlineable initializer."
    }

    /**
     * Walks every [KtFile] in [projectKtFiles] and collects every [KtReferenceExpression] whose
     * `mainReference` resolves to the [property] PSI element.
     *
     * @param property the declaration whose references to collect
     * @return references grouped per containing [KtFile]
     */
    private fun findUsages(property: KtProperty): Map<KtFile, List<KtReferenceExpression>> {
        val target: PsiElement = property
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
