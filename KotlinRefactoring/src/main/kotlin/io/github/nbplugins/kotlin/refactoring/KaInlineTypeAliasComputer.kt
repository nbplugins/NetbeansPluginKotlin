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

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.k2.refactoring.inline.codeInliner.TypeAliasUsageReplacementStrategy
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Prepared IDEA-engine data for a Kotlin **Inline Type Alias** refactoring.
 *
 * @param typeAlias declaration selected for expansion
 * @param strategy actual IDEA K2 replacement strategy responsible for type substitution and shortening
 * @param usages Kotlin references resolving to [typeAlias], grouped by their session-managed file
 * @param triggerUsage reference at the invocation caret, when Inline was started from an alias use
 * @param declarationName simple type-alias name for the UI
 */
data class KaInlineTypeAliasResult(
    val typeAlias: KtTypeAlias,
    val strategy: TypeAliasUsageReplacementStrategy,
    val usages: Map<KtFile, List<KtReferenceExpression>>,
    val triggerUsage: KtReferenceExpression?,
    val declarationName: String,
)

/**
 * User-facing validation failure returned by [KaInlineTypeAliasComputer].
 *
 * @param message explanation of why the selected alias cannot be inlined
 * @param declarationName alias name, when available
 */
data class KaInlineTypeAliasError(
    val message: String,
    val declarationName: String,
)

/**
 * Creates a standalone K2 plan for Kotlin **Inline Type Alias**.
 *
 * The computer uses IDEA's [TypeAliasUsageReplacementStrategy] rather than reconstructing an alias
 * expansion as text. NetBeans owns the dialog, preview, persistence, and undo; the IDEA strategy owns
 * K2 type substitution, parenthesisation, imports, and reference shortening.
 *
 * @param cursorKtFile session-managed Kotlin file containing the invocation caret
 * @param offset caret offset within [cursorKtFile]
 * @param projectKtFiles all Kotlin source files currently registered by the standalone session
 */
class KaInlineTypeAliasComputer(
    private val cursorKtFile: KtFile,
    private val offset: Int,
    private val projectKtFiles: Collection<KtFile>,
) {

    /** Result of [compute]. */
    sealed class Outcome {
        /** The target alias and IDEA replacement plan are ready. */
        data class Ready(val result: KaInlineTypeAliasResult) : Outcome()

        /** A type alias was selected but lacks data required by IDEA's replacement engine. */
        data class Error(val error: KaInlineTypeAliasError) : Outcome()

        /** The caret is neither on a type alias declaration nor a reference to one. */
        data object NotApplicable : Outcome()
    }

    /**
     * Resolves the type alias at the caret and discovers portable Kotlin usages across the active session.
     *
     * @return [Outcome.Ready] with the IDEA strategy, [Outcome.Error] for an invalid alias, or
     *         [Outcome.NotApplicable] when no type alias is selected
     */
    fun compute(): Outcome {
        val element = cursorKtFile.findElementAt(offset) ?: return Outcome.NotApplicable
        val triggerUsage = PsiTreeUtil.getParentOfType(element, KtReferenceExpression::class.java, false)
        val typeAlias = resolveTypeAliasFromReference(element)
            ?: PsiTreeUtil.getParentOfType(element, KtTypeAlias::class.java, false)
            ?: return Outcome.NotApplicable
        val declarationName = typeAlias.name ?: return Outcome.Error(
            KaInlineTypeAliasError("Cannot inline type alias: it has no name.", ""),
        )
        if (typeAlias.getTypeReference() == null) {
            return Outcome.Error(
                KaInlineTypeAliasError(
                    "Cannot inline type alias '$declarationName': it has no expanded type.",
                    declarationName,
                ),
            )
        }

        return Outcome.Ready(
            KaInlineTypeAliasResult(
                typeAlias = typeAlias,
                strategy = TypeAliasUsageReplacementStrategy(typeAlias),
                usages = findUsages(typeAlias),
                triggerUsage = triggerUsage?.takeIf { resolvesTo(it, typeAlias) },
                declarationName = declarationName,
            ),
        )
    }

    /** Resolves a reference at the caret to its declared [KtTypeAlias], when applicable. */
    private fun resolveTypeAliasFromReference(element: PsiElement): KtTypeAlias? {
        val reference = PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false)
            ?: return null
        return runCatching {
            analyze(reference) {
                reference.mainReference?.resolveToSymbol()?.psi as? KtTypeAlias
            }
        }.getOrNull()
    }

    /** Collects K2-resolved Kotlin references to [typeAlias] from every active source file. */
    private fun findUsages(typeAlias: KtTypeAlias): Map<KtFile, List<KtReferenceExpression>> {
        val usagesByFile = linkedMapOf<KtFile, MutableList<KtReferenceExpression>>()
        for (file in projectKtFiles) {
            val usages = mutableListOf<KtReferenceExpression>()
            runCatching {
                analyze(file) {
                    file.accept(object : KtTreeVisitorVoid() {
                        override fun visitReferenceExpression(expression: KtReferenceExpression) {
                            super.visitReferenceExpression(expression)
                            if (resolvesTo(expression, typeAlias)) usages += expression
                        }
                    })
                }
            }
            if (usages.isNotEmpty()) usagesByFile[file] = usages
        }
        return usagesByFile
    }

    /** Checks K2 symbol identity rather than spelling so unrelated same-name aliases are excluded. */
    private fun resolvesTo(reference: KtReferenceExpression, typeAlias: KtTypeAlias): Boolean =
        runCatching {
            analyze(reference) {
                reference.mainReference?.resolveToSymbol()?.psi == typeAlias
            }
        }.getOrDefault(false)
}
