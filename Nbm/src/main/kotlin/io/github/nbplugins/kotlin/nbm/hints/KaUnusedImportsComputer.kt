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
 * Detects unused import directives in a Kotlin file using the K2 Analysis API.
 *
 * Only explicit, non-star, non-aliased imports are checked. Star imports and aliased imports
 * are conservatively considered used. Each import is compared against the set of fully-qualified
 * symbol names actually referenced in the file body (resolved via [analyze]).
 *
 * Limitation: companion-object imports and some operator-overload imports may produce false
 * negatives (import appears used) or false positives. These are acceptable in this version.
 *
 * @param parserResult the parser result providing file metadata and document access
 * @param kaKtFile K2-session-owned [KtFile] for this file; references are resolved against it
 */
class KaUnusedImportsComputer(
    private val parserResult: KotlinParserResult,
    private val kaKtFile: KtFile,
) {

    /**
     * Returns a [Hint] for each import directive that is not referenced anywhere in the file body.
     *
     * @return list of [HintSeverity.WARNING] hints, one per unused import; empty when all imports
     *         are used or when the file has no imports
     */
    fun getUnusedImports(): List<Hint> {
        val fileObject = parserResult.snapshot?.source?.fileObject ?: return emptyList()

        val candidates = kaKtFile.importDirectives.filter { directive ->
            val path = directive.importPath ?: return@filter false
            !path.isAllUnder && !path.hasAlias()
        }
        if (candidates.isEmpty()) return emptyList()

        val referencedFqNames = collectReferencedFqNames()

        return candidates
            .filter { directive -> directive.importPath?.fqName !in referencedFqNames }
            .map { directive -> unusedImportHint(directive, fileObject) }
    }

    /**
     * Walks every [KtSimpleNameExpression] in the file body (excluding import and package
     * directives) and resolves each one to a K2 symbol, collecting fully-qualified names.
     *
     * Uses [KaClassLikeSymbol.classId] for class/interface/object symbols and
     * [KaCallableSymbol.callableId] for function/property symbols.
     */
    private fun collectReferencedFqNames(): Set<FqName> {
        val nameExpressions = PsiTreeUtil
            .collectElementsOfType(kaKtFile, KtSimpleNameExpression::class.java)
            .filter { expr ->
                PsiTreeUtil.getParentOfType(expr, KtImportDirective::class.java) == null &&
                PsiTreeUtil.getParentOfType(expr, KtPackageDirective::class.java) == null
            }

        val result = mutableSetOf<FqName>()
        runCatching {
            analyze(kaKtFile) {
                for (expr in nameExpressions) {
                    val ref = expr.references.filterIsInstance<KtReference>().firstOrNull() ?: continue
                    when (val sym = ref.resolveToSymbol()) {
                        is KaClassLikeSymbol -> sym.classId?.asSingleFqName()?.let { result += it }
                        is KaCallableSymbol  -> sym.callableId?.asSingleFqName()?.let { result += it }
                        else -> Unit
                    }
                }
            }
        }.onFailure {
            KotlinLogger.INSTANCE.logWarning(
                "KaUnusedImportsComputer: reference resolution failed for ${kaKtFile.name}: $it"
            )
        }
        return result
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
