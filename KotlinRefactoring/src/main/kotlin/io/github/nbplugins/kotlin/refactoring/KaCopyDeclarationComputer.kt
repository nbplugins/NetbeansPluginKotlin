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
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf

/**
 * Headless analysis engine for the **Copy Declaration** refactoring.
 *
 * Ported from IDEA's `CopyKotlinDeclarationsHandler` (K2 variant) and
 * `K2MoveRenameUsageInfo.markInternalUsages` / `retargetInternalUsagesForCopyFile`.
 *
 * The engine finds the top-level named declaration under the caret, then uses K2 to resolve
 * every reference inside that declaration and determine which import statements the new file
 * will need.  This mirrors the "retargeting" step from IDEA's copy handler:
 *
 *  - In IDEA: `markInternalUsages` annotates PSI references with K2 data, then
 *    `retargetInternalUsagesForCopyFile` calls `reference.bindToElement()` / `shortenReferences`
 *    after the PSI copy.
 *  - Here (NetBeans): we collect the required import FQNs upfront (no PSI mutation) and
 *    write them verbatim into the new file's import block.
 *
 * **What is retargeted**: every reference expression in the declaration whose resolved FQN
 * is neither a Kotlin/Java default import, nor a local declaration, nor a parameter — and
 * which is explicitly imported in the source file or lives in a different package.  The
 * resulting [KaCopyDeclarationResult.neededImports] list is passed to the apply step so it
 * can prepend the correct imports to the new file content.
 *
 * @param ktFile       the source file
 * @param caretOffset  caret position within the file
 */
class KaCopyDeclarationComputer(
    private val ktFile: KtFile,
    private val caretOffset: Int,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret is not inside a top-level named declaration. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaCopyDeclarationResult) : Outcome()
    }

    /**
     * Runs the analysis and returns an [Outcome].
     *
     * Returns [Outcome.NotApplicable] when the caret is not inside a top-level [KtNamedDeclaration].
     */
    fun compute(): Outcome = try {
        computeInternal()
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    private fun computeInternal(): Outcome {
        val leaf = ktFile.findElementAt(caretOffset) ?: return Outcome.NotApplicable

        // Walk up to the nearest KtNamedDeclaration that is a direct child of KtFile.
        val declaration = leaf.parentsWithSelf
            .filterIsInstance<KtNamedDeclaration>()
            .firstOrNull { it.parent is KtFile }
            ?: return Outcome.NotApplicable

        val name = declaration.name ?: return Outcome.NotApplicable
        val packageName = ktFile.packageFqName.asString()
        val suggestedFileName = "$name.kt"

        // Collect imports needed in the new file via K2 retargeting analysis.
        val neededImports = collectNeededImports(declaration, packageName)

        return Outcome.Ready(
            KaCopyDeclarationResult(
                declarationRange = declaration.textRange,
                declarationText = declaration.text,
                declarationName = name,
                packageName = packageName,
                suggestedFileName = suggestedFileName,
                neededImports = neededImports,
            )
        )
    }

    /**
     * Collects the set of import FQN strings that the copied declaration will need in its new file.
     *
     * Mirrors the retargeting logic from IDEA's `K2MoveRenameUsageInfo.markInternalUsages` +
     * `retargetInternalUsagesForCopyFile`:
     *
     *  1. Walk all [KtReferenceExpression]s inside [declaration] (same as `internalUsageElements()`).
     *  2. For each reference, resolve it via K2 to get an [FqName].
     *  3. Skip references that are:
     *     - Already in scope via the source package (same-package symbols)
     *     - Covered by Kotlin or Java default imports
     *     - Imported via a star-import that is preserved
     *  4. Include any explicit import from the source file that the reference needs.
     *
     * The result is a sorted, deduplicated list of `"import pkg.Class"` strings.
     *
     * @param declaration the top-level declaration being copied
     * @param sourcePackage the package of the source file (e.g. `"com.example"`)
     */
    private fun collectNeededImports(declaration: KtNamedDeclaration, sourcePackage: String): List<String> {
        // Build a map of FQN -> import text from the source file's import block.
        val sourceImportsByFqn: Map<String, String> = ktFile.importDirectives
            .filter { !it.isAllUnder && it.aliasName == null }
            .mapNotNull { directive ->
                val fqn = directive.importedFqName?.asString() ?: return@mapNotNull null
                fqn to "import $fqn"
            }.toMap()

        // Star-imports: preserve them as-is (e.g. "import com.example.util.*").
        val starImports: List<String> = ktFile.importDirectives
            .filter { it.isAllUnder }
            .mapNotNull { it.importedFqName?.asString()?.let { fqn -> "import $fqn.*" } }

        // Collect all reference expressions in the declaration body.
        val refs = declaration.collectDescendantsOfType<KtReferenceExpression> { refExpr ->
            // Skip references inside the package directive (shouldn't occur inside a declaration, but be safe).
            PsiTreeUtil.getParentOfType(refExpr, KtPackageDirective::class.java) == null &&
                    PsiTreeUtil.getParentOfType(refExpr, KtImportDirective::class.java) == null
        }

        val needed = linkedSetOf<String>()

        // Add all star-imports from the source file — they may be needed.
        needed.addAll(starImports)

        // Resolve each reference and determine if an import is needed.
        runCatching {
            analyze(ktFile) {
                for (ref in refs) {
                    val fqName: FqName = runCatching {
                        val sym = ref.mainReference?.resolveToSymbol() ?: return@runCatching null
                        when (sym) {
                            is KaConstructorSymbol -> sym.containingClassId?.asSingleFqName()
                            is KaClassLikeSymbol -> sym.classId?.asSingleFqName()
                            is KaCallableSymbol -> sym.callableId?.asSingleFqName()
                            else -> null
                        }
                    }.getOrNull() ?: continue

                    val fqnStr = fqName.asString()
                    val pkg = fqName.parent().asString()

                    // Skip symbols in same package — they'll be in scope without imports.
                    if (pkg == sourcePackage) continue

                    // Skip Kotlin / Java default imports.
                    if (isDefaultImport(pkg)) continue

                    // Look up an explicit source-file import for this FQN.
                    val importText = sourceImportsByFqn[fqnStr] ?: "import $fqnStr"
                    needed.add(importText)
                }
            }
        }

        return needed.sorted()
    }

    /**
     * Returns `true` when [packageName] is covered by Kotlin's or Java's default import rules.
     *
     * Mirrors the logic used in `KotlinImportInsertHelper.isImportNeeded` / `isAlreadyImported`.
     * The listed packages are the standard auto-imports applied to every Kotlin file on JVM.
     */
    private fun isDefaultImport(packageName: String): Boolean = packageName in DEFAULT_IMPORT_PACKAGES

    companion object {
        /** Packages available in every Kotlin file without an explicit import. */
        private val DEFAULT_IMPORT_PACKAGES = setOf(
            "kotlin",
            "kotlin.annotation",
            "kotlin.collections",
            "kotlin.comparisons",
            "kotlin.io",
            "kotlin.ranges",
            "kotlin.sequences",
            "kotlin.text",
            "java.lang",
        )
    }
}

/**
 * All data needed by the NetBeans apply element to perform the copy-declaration transformation.
 *
 * @param declarationRange   range of the declaration in the source file
 * @param declarationText    full text of the declaration (copied verbatim to the target file)
 * @param declarationName    simple name of the declaration (e.g. `"Foo"`, `"greet"`)
 * @param packageName        fully-qualified package name of the source file (e.g. `"com.example"`)
 * @param suggestedFileName  default file name for the copy (e.g. `"Foo.kt"`)
 * @param neededImports      sorted list of `"import pkg.Class"` (and star imports) that the
 *                           copied declaration needs in its new file; determined by K2 retargeting
 */
data class KaCopyDeclarationResult(
    val declarationRange: TextRange,
    val declarationText: String,
    val declarationName: String,
    val packageName: String,
    val suggestedFileName: String,
    val neededImports: List<String> = emptyList(),
)
