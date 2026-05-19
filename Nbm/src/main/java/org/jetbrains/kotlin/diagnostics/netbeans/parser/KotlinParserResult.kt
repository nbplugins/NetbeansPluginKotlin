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
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.api.project.Project
import org.netbeans.modules.csl.api.Error
import org.netbeans.modules.csl.spi.ParserResult
import org.netbeans.modules.parsing.api.Snapshot
import org.openide.filesystems.FileObject

/**
 * Holds the result of parsing and analysing a single Kotlin file.
 *
 * Analysis uses the K2 Analysis API via `analyze(kaKtFile) { collectDiagnostics(...) }`.
 * Syntax errors (from the PSI parser) are always included via [KotlinSyntaxError].
 *
 * @param snapshot  the NetBeans snapshot that was parsed
 * @param ktFile    PSI file used for syntax error extraction
 * @param file      the NetBeans [FileObject] for this source file
 * @param project   the enclosing NetBeans project
 * @param kaKtFile  K2-session-owned [KtFile]; null when no K2 session is available
 */
class KotlinParserResult(
    snapshot: Snapshot?,
    val ktFile: KtFile,
    val file: FileObject,
    val project: Project,
    val kaKtFile: KtFile? = null,
) : ParserResult(snapshot) {

    override fun invalidate() {}

    override fun getDiagnostics(): List<Error> = buildList {
        if (kaKtFile != null) {
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
            }
        }
        // Syntax errors from the PSI parser always included
        addAll(PsiTreeUtil.collectElementsOfType(ktFile, PsiErrorElement::class.java).map { KotlinSyntaxError(it, file) })
    }
}
