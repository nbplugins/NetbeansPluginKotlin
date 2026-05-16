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
package io.github.nbplugins.kotlin.nbm.diagnostics

import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.netbeans.modules.csl.api.Error.Badging
import org.netbeans.modules.csl.api.Severity
import org.openide.filesystems.FileObject

/**
 * NetBeans [Badging] implementation backed by a K2 [KaDiagnosticWithPsi].
 *
 * Used as the K2 replacement for [org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinError]
 * on the K2 analysis path in [org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult].
 *
 * @param diagnostic the K2 diagnostic reported by the Analysis API
 * @param fileObject the NetBeans file containing the diagnostic
 */
class KaDiagnosticError(
    val kaDiagnostic: KaDiagnosticWithPsi<*>,
    private val fileObject: FileObject,
) : Badging {

    override fun showExplorerBadge(): Boolean = kaDiagnostic.severity == KaSeverity.ERROR

    override fun getDisplayName(): String = kaDiagnostic.defaultMessage

    override fun getDescription(): String = ""

    override fun getKey(): String = kaDiagnostic.factoryName

    override fun getFile(): FileObject = fileObject

    override fun getStartPosition(): Int = kaDiagnostic.textRanges.firstOrNull()?.startOffset ?: 0

    override fun getEndPosition(): Int = kaDiagnostic.textRanges.firstOrNull()?.endOffset ?: 0

    override fun isLineError(): Boolean = startPosition == endPosition

    override fun getSeverity(): Severity = when (kaDiagnostic.severity) {
        KaSeverity.ERROR   -> Severity.ERROR
        KaSeverity.WARNING -> Severity.WARNING
        KaSeverity.INFO    -> Severity.INFO
    }

    override fun getParameters(): Array<Any>? = null
}
