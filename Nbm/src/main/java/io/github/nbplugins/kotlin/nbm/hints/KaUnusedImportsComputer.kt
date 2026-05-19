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
package io.github.nbplugins.kotlin.nbm.hints

import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix

/**
 * K2 Analysis API port of unused-import detection.
 *
 * KaImportOptimizer is absent from analysis-api-for-ide:2.3.20-ij253-119; import analysis
 * is disabled until a compatible API is available (tracked in D5 follow-up).
 *
 * @param parserResult the parser result providing file metadata and document
 * @param kaKtFile K2-session-owned [KtFile] for this file
 */
class KaUnusedImportsComputer(
    @Suppress("UNUSED_PARAMETER") private val parserResult: KotlinParserResult,
    @Suppress("UNUSED_PARAMETER") private val kaKtFile: KtFile
) {

    /** Returns an empty list; import analysis unavailable in this API build. */
    fun getUnusedImports(): List<Hint> = emptyList()
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
