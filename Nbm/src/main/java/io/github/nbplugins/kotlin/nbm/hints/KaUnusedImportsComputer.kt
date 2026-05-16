/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaIdeApi::class)
package io.github.nbplugins.kotlin.nbm.hints

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.language.Priorities
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.hints.KotlinRule
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.UnusedImportsComputer].
 *
 * Uses [org.jetbrains.kotlin.analysis.api.components.KaImportOptimizer.analyseImports] to
 * determine which import directives are not referenced in the file body.  An import is
 * considered unused when its fully-qualified name is absent from [usedDeclarations] AND its
 * short name is not among [unresolvedNames] (unresolved names are kept to avoid false positives).
 *
 * @param parserResult the parser result providing file metadata and document
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaUnusedImportsComputer(
    private val parserResult: KotlinParserResult,
    private val kaKtFile: KtFile
) {

    /**
     * Returns a list of [Hint] objects for each unused import directive.
     *
     * Each hint includes a [KaUnusedImportHintFix] that removes the import from the document.
     *
     * @return list of warning-level hints for unused imports
     */
    fun getUnusedImports(): List<Hint> {
        val unusedImports = mutableListOf<KtImportDirective>()

        analyze(kaKtFile) {
            val result = runCatching { analyseImports(kaKtFile) }.getOrNull() ?: return@analyze
            val usedDeclarations = result.usedDeclarations
            val unresolvedNames = result.unresolvedNames

            for (directive in kaKtFile.importDirectives) {
                val importPath = directive.importPath ?: continue
                if (importPath.isAllUnder) continue  // star imports are handled conservatively

                val fqName = importPath.fqName
                val shortName = importPath.importedName ?: continue

                // Keep import if its short name is among unresolved names (avoids false positives).
                if (shortName in unresolvedNames) continue

                // Import is used if usedDeclarations contains an entry for this FQ name
                // with the short name in its value set.
                val isUsed = usedDeclarations[fqName]?.contains(shortName) == true
                if (!isUsed) unusedImports.add(directive)
            }
        }

        // Map kaKtFile directives back to parserResult.ktFile directives by text range
        // to ensure document offsets are from the live document tree.
        val ktImports = parserResult.ktFile.importDirectives.associateBy { it.textRange }

        return unusedImports.mapNotNull { kaDirective ->
            val directive = ktImports[kaDirective.textRange] ?: kaDirective
            Hint(
                KotlinRule(HintSeverity.WARNING),
                "Unused import: ${directive.importedFqName}",
                parserResult.snapshot.source.fileObject,
                OffsetRange(directive.textRange.startOffset, directive.textRange.endOffset),
                listOf(KaUnusedImportHintFix(parserResult, directive)),
                Priorities.HINT_PRIORITY
            )
        }
    }
}

/**
 * Quick fix that removes an unused import directive from the document.
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
