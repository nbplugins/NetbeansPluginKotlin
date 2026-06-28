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

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.idea.references.mainReference

/**
 * Headless analysis engine for the **Introduce Import Alias** refactoring.
 *
 * Ported from IDEA's `KotlinIntroduceImportAliasHandler` (K2 variant, ~175 LOC).  Unlike the
 * original, which uses `ReferencesSearch` and `VariableInplaceRenamer`, this class operates in
 * standalone mode (no IDE index) and collects usages by PSI name-matching with optional K2
 * symbol verification.
 *
 * Trigger points (mirrors IDEA behaviour):
 *  - **Import directive** — cursor is anywhere on an `import pkg.Class` line
 *  - **Name reference** — cursor is on any `KtNameReferenceExpression` in the file body;
 *    the engine resolves the referenced symbol via K2, locates the corresponding import directive,
 *    and proceeds as in the import-directive case
 *
 * Both star-imports and already-aliased imports are rejected as not applicable.
 *
 * @param ktFile       the file being refactored
 * @param caretOffset  caret position within the file
 */
class KaIntroduceImportAliasComputer(
    private val ktFile: KtFile,
    private val caretOffset: Int,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret is not on a suitable import directive or name reference. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaIntroduceImportAliasResult) : Outcome()
    }

    /**
     * Runs the analysis and returns an [Outcome].
     *
     * Returns [Outcome.NotApplicable] when:
     * - the caret is not on an import directive or a resolvable name reference,
     * - the import is a star-import (`import pkg.*`),
     * - the import already has an alias,
     * - there is no explicit import for the resolved FQN (e.g. same-package declaration).
     */
    fun compute(): Outcome = try {
        computeInternal()
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    private fun computeInternal(): Outcome {
        val leaf = ktFile.findElementAt(caretOffset) ?: return Outcome.NotApplicable

        // Priority 1: cursor is on an import directive line.
        val importDirective = PsiTreeUtil.getParentOfType(leaf, KtImportDirective::class.java, false)
        if (importDirective != null) return computeFromImportDirective(importDirective)

        // Priority 2: cursor is on a name reference expression anywhere in the file.
        val nameRef = PsiTreeUtil.getParentOfType(leaf, KtNameReferenceExpression::class.java, false)
            ?: return Outcome.NotApplicable

        return computeFromNameReference(nameRef)
    }

    private fun computeFromImportDirective(importDirective: KtImportDirective): Outcome {
        if (importDirective.isAllUnder) return Outcome.NotApplicable
        if (importDirective.aliasName != null) return Outcome.NotApplicable

        val shortName = importDirective.importedName?.asString() ?: return Outcome.NotApplicable
        val importedFqn = importDirective.importedFqName ?: return Outcome.NotApplicable

        val usageRanges = collectUsagesByName(shortName)
        return Outcome.Ready(
            KaIntroduceImportAliasResult(
                importDirectiveRange = importDirective.textRange,
                importedFqn = importedFqn.asString(),
                shortName = shortName,
                usageRanges = usageRanges,
            )
        )
    }

    /**
     * Resolves the name reference to a symbol via K2, extracts its FQN, then locates the
     * matching (un-aliased) import directive and delegates to [computeFromImportDirective].
     *
     * This mirrors the first part of `KotlinIntroduceImportAliasHandler.doRefactoring()` where
     * `element.mainReference.resolve()` followed by descriptor traversal gives the FQN, and
     * `file.importDirectives` is searched for that FQN.
     */
    private fun computeFromNameReference(nameRef: KtNameReferenceExpression): Outcome {
        // Caret is inside an import directive (e.g. user clicked on "Class" in "import pkg.Class").
        val parentImport = PsiTreeUtil.getParentOfType(nameRef, KtImportDirective::class.java)
        if (parentImport != null) return computeFromImportDirective(parentImport)

        // Resolve via K2 to get the FQN of the referenced symbol.
        val fqName: FqName = runCatching {
            analyze(ktFile) {
                val sym = nameRef.mainReference?.resolveToSymbol() ?: return@analyze null
                // KaConstructorSymbol must be checked before KaCallableSymbol because it IS a
                // KaCallableSymbol and its callableId gives the wrong FQN (contains "<init>").
                when (sym) {
                    is KaConstructorSymbol -> sym.containingClassId?.asSingleFqName()
                    is KaClassLikeSymbol   -> sym.classId?.asSingleFqName()
                    is KaCallableSymbol    -> sym.callableId?.asSingleFqName()
                    else -> null
                }
            }
        }.getOrNull() ?: return Outcome.NotApplicable

        // Find the un-aliased import directive for this FQN.
        val importDirective = ktFile.importDirectives
            .find { it.aliasName == null && !it.isAllUnder && it.importedFqName == fqName }
            ?: return Outcome.NotApplicable // same-package symbol or already aliased

        return computeFromImportDirective(importDirective)
    }

    /**
     * Collects text ranges of all [KtSimpleNameExpression]s in the file body whose referenced name
     * equals [shortName], excluding import directives and package declarations.
     *
     * This is a PSI name-based scan — equivalent to what IDEA's `ReferencesSearch.search()` returns
     * for single-file scope, but without the index infrastructure.
     */
    private fun collectUsagesByName(shortName: String): List<TextRange> =
        PsiTreeUtil.collectElementsOfType(ktFile, KtSimpleNameExpression::class.java)
            .filter { ref ->
                ref.getReferencedName() == shortName &&
                        PsiTreeUtil.getParentOfType(ref, KtImportDirective::class.java) == null &&
                        PsiTreeUtil.getParentOfType(ref, KtPackageDirective::class.java) == null
            }
            .map { it.textRange }
}

/**
 * All data needed by the NetBeans apply element to perform the introduce-import-alias transformation.
 *
 * @param importDirectiveRange  text range of the entire `import pkg.Class` directive (no trailing newline)
 * @param importedFqn           fully-qualified name being imported, e.g. `"com.example.MyClass"`
 * @param shortName             the imported short name, e.g. `"MyClass"` (or the alias target)
 * @param usageRanges           text ranges of all body [KtSimpleNameExpression]s with name [shortName]
 *                              (excluding import and package directives)
 */
data class KaIntroduceImportAliasResult(
    val importDirectiveRange: TextRange,
    val importedFqn: String,
    val shortName: String,
    val usageRanges: List<TextRange>,
)
