/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.diagnostics.netbeans.parser

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.resolve.AnalyzingUtils
import org.jetbrains.kotlin.resolve.AnalysisResultWithProvider
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.api.project.Project
import org.netbeans.modules.csl.api.Error
import org.netbeans.modules.csl.spi.ParserResult
import org.netbeans.modules.parsing.api.Snapshot
import org.openide.filesystems.FileObject

/**
 * Holds the result of parsing and analysing a single Kotlin file.
 *
 * Two analysis paths are supported:
 * - **K2 (primary)**: when [kaKtFile] is non-null, [getDiagnostics] uses the K2 Analysis API
 *   via `analyze(kaKtFile) { collectDiagnostics(...) }`.
 * - **K1 (fallback)**: when [kaKtFile] is null, [getDiagnostics] falls back to the legacy
 *   [AnalysisResultWithProvider] / [org.jetbrains.kotlin.resolve.BindingContext] path.
 *
 * Syntax errors (from the PSI parser) are always included regardless of path.
 *
 * @param snapshot        the NetBeans snapshot that was parsed
 * @param analysisResult  K1 analysis result; may be null if analysis failed
 * @param ktFile          K1 PSI file (used for syntax errors and K1 fallback)
 * @param file            the NetBeans [FileObject] for this source file
 * @param project         the enclosing NetBeans project
 * @param kaKtFile        K2-session-owned [KtFile] for this file; null when K2 is unavailable
 */
class KotlinParserResult(
    snapshot: Snapshot?,
    val analysisResult: AnalysisResultWithProvider?,
    val ktFile: KtFile,
    val file: FileObject,
    val project: Project,
    val kaKtFile: KtFile? = null,
) : ParserResult(snapshot) {

    override fun invalidate() {}

    override fun getDiagnostics(): List<Error> = buildList {
        if (kaKtFile != null) {
            // K2 path (primary)
            runCatching {
                analyze(kaKtFile) {
                    val k2Diagnostics = kaKtFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
                        .filter { it.severity != KaSeverity.INFO }
                    KotlinLogger.INSTANCE.logInfo(
                        "K2 diagnostics for ${file.name}: ${k2Diagnostics.size} diagnostic(s)"
                    )
                    addAll(k2Diagnostics.map { diag -> KaDiagnosticError(diag, file) })
                }
            }.onFailure {
                KotlinLogger.INSTANCE.logWarning("K2 diagnostics failed for ${file.path}: $it")
                // K1 fallback on K2 failure
                if (analysisResult != null) addK1Diagnostics()
            }
        } else if (analysisResult != null) {
            // K1 fallback when K2 session is unavailable
            addK1Diagnostics()
        }
        // Syntax errors from the PSI parser always included
        addAll(AnalyzingUtils.getSyntaxErrorRanges(ktFile).map { KotlinSyntaxError(it, file) })
    }

    private fun MutableList<Error>.addK1Diagnostics() {
        addAll(
            analysisResult!!.analysisResult.bindingContext.diagnostics.all()
                .filter { it.psiFile == ktFile }
                .filter { it.factory != Errors.DEPRECATION }
                .map { KotlinError(it, file) }
        )
    }
}
