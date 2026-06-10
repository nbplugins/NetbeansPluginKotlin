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
package io.github.nbplugins.kotlin.nbm.hints

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import org.openide.filesystems.FileObject

/**
 * Detects unused import directives in a Kotlin file using a conservative two-pass algorithm.
 *
 * Pass 1 (PSI only, no [analyze]): collect the set of short identifiers appearing as
 * [KtSimpleNameExpression] anywhere in the file body, excluding the package directive and
 * the import list itself.
 *
 * Pass 2 (per import): for each non-star, non-aliased [KtImportDirective] take its imported
 * short name. If the short name is absent from the Pass 1 set, the import is reported as
 * unused. If the short name *is* present, optionally try to resolve a usage via [analyze]
 * to detect shadowing — only when resolution succeeds and the resolved fully-qualified name
 * differs from the import's [FqName] for **every** occurrence, the import is reported as
 * unused. When resolution fails or returns no symbol (common when an external project's
 * classpath is only partially indexed), the import is conservatively considered used.
 *
 * Star imports (`import foo.*`) and aliased imports (`import foo.Bar as Baz`) are always
 * considered used.
 *
 * Limitation: extension functions, operators and other imports whose short name happens to
 * appear in the file but whose actual binding cannot be confirmed by resolution are treated
 * as used. This trades a small number of true positives for the elimination of false
 * positives that would otherwise plague any file whose dependencies the K2 session cannot
 * fully resolve.
 *
 * @param parserResult the parser result providing file metadata and document access
 * @param kaKtFile K2-session-owned [KtFile] for this file; references are resolved against it
 */
class KaUnusedImportsComputer(
    private val parserResult: KotlinParserResult,
    private val kaKtFile: KtFile,
) {

    /**
     * Returns a [Hint] for each import directive deemed unused by the two-pass algorithm
     * described in the class KDoc.
     *
     * @return list of [HintSeverity.WARNING] hints; empty when no imports are reported unused
     */
    fun getUnusedImports(): List<Hint> {
        val fileObject = parserResult.snapshot?.source?.fileObject ?: parserResult.file

        val candidates = kaKtFile.importDirectives.filter { directive ->
            val path = directive.importPath ?: return@filter false
            !path.isAllUnder && !path.hasAlias()
        }
        if (candidates.isEmpty()) return emptyList()

        val bodyNameExpressions = collectBodyNameExpressions()
        val shortNamesInBody: Set<String> = bodyNameExpressions
            .mapNotNullTo(mutableSetOf()) { it.getReferencedName() }

        return candidates
            .filter { directive -> isUnused(directive, shortNamesInBody, bodyNameExpressions) }
            .map { directive -> unusedImportHint(directive, fileObject) }
    }

    /**
     * Returns every [KtSimpleNameExpression] in the file outside import and package directives.
     */
    private fun collectBodyNameExpressions(): List<KtSimpleNameExpression> =
        PsiTreeUtil
            .collectElementsOfType(kaKtFile, KtSimpleNameExpression::class.java)
            .filter { expr ->
                PsiTreeUtil.getParentOfType(expr, KtImportDirective::class.java) == null &&
                PsiTreeUtil.getParentOfType(expr, KtPackageDirective::class.java) == null
            }

    /**
     * Decides whether a single import directive is unused.
     *
     * Fast path: if the import's short name never appears in the file body, the import is
     * unused. Otherwise the function tries to resolve every body occurrence of that short
     * name to detect a shadowing import; an import is reported unused only if **every**
     * successfully resolved occurrence bound to a different [FqName] than the one this
     * directive imports. Unresolved occurrences make the import conservatively used.
     */
    private fun isUnused(
        directive: KtImportDirective,
        shortNamesInBody: Set<String>,
        bodyNameExpressions: List<KtSimpleNameExpression>,
    ): Boolean {
        val path = directive.importPath ?: return false
        val importFqName = path.fqName
        val shortName = path.importedName?.asString() ?: importFqName.shortName().asString()

        if (shortName !in shortNamesInBody) return true

        val occurrences = bodyNameExpressions.filter { it.getReferencedName() == shortName }
        if (occurrences.isEmpty()) return true

        // Try to confirm shadowing. If any occurrence resolves to this import's FqName, used.
        // If no occurrence successfully resolves, conservatively treat as used.
        var anyResolved = false
        var anyMatched = false
        runCatching {
            analyze(kaKtFile) {
                for (expr in occurrences) {
                    val ref = expr.references.filterIsInstance<KtReference>().firstOrNull() ?: continue
                    val sym = ref.resolveToSymbol() ?: continue
                    val resolvedFqName: FqName? = when (sym) {
                        is KaClassLikeSymbol -> sym.classId?.asSingleFqName()
                        is KaCallableSymbol  -> sym.callableId?.asSingleFqName()
                        else -> null
                    } ?: continue
                    anyResolved = true
                    if (resolvedFqName == importFqName) {
                        anyMatched = true
                        break
                    }
                }
            }
        }.onFailure {
            KotlinLogger.INSTANCE.logWarning(
                "KaUnusedImportsComputer: shadow-check failed for ${kaKtFile.name}: $it"
            )
            return false
        }

        // Unused only when we successfully resolved at least one occurrence and none of them
        // bound to this import. Otherwise (nothing resolved, or one matched), treat as used.
        return anyResolved && !anyMatched
    }

    private fun unusedImportHint(directive: KtImportDirective, fileObject: FileObject): Hint =
        Hint(
            KotlinRule(HintSeverity.WARNING),
            "Unused import",
            fileObject,
            OffsetRange(directive.textRange.startOffset, directive.textRange.endOffset),
            listOf(KaUnusedImportHintFix(parserResult, directive)),
            30
        )
}

/**
 * Quick fix that removes an unused import directive from the document.
 *
 * Removes the import line including the preceding newline so that no blank line is left behind.
 *
 * @param parserResult used to obtain the Swing document
 * @param importDirective the import to remove
 */
class KaUnusedImportHintFix(
    private val parserResult: KotlinParserResult,
    private val importDirective: KtImportDirective
) : HintFix {

    override fun isSafe(): Boolean = true

    override fun isInteractive(): Boolean = false

    override fun getDescription(): String = "Remove unused import"

    override fun implement() {
        val doc = parserResult.snapshot.source.getDocument(false)
        val startOffset = importDirective.textRange.startOffset - 1
        val length = importDirective.textRange.endOffset - startOffset
        doc.remove(startOffset, length)
    }
}
