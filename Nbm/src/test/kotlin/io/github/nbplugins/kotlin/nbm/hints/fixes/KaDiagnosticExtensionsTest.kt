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
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.builder.KotlinPsiManager
import utils.KotlinTestCase

/**
 * Unit tests for [KaDiagnosticExtensions.typed].
 *
 * Verifies that [typed] correctly casts a [KaDiagnosticError] to the requested diagnostic
 * subtype when the type matches, and returns null when it does not.
 */
class KaDiagnosticExtensionsTest : KotlinTestCase("KaDiagnosticExtensions", "quickfixes") {

    private fun getKaKtFileOrSkip(path: String) =
        KotlinAnalysisAPISession.getSession(project).takeIf { it.hasDependencies }
            ?.getKtFileForPath(path)

    private fun getDiagnostics(kaKtFile: org.jetbrains.kotlin.psi.KtFile): List<KaDiagnosticError> {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return emptyList()
        val ktFile = KotlinPsiManager.getParsedFile(file) ?: return emptyList()
        return KotlinParserResult(null, ktFile, file, project, kaKtFile)
            .getDiagnostics().filterIsInstance(KaDiagnosticError::class.java)
    }

    /** typed() returns a non-null result when the diagnostic matches the requested type. */
    fun testTypedReturnsNonNullForMatchingType() {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val errors = getDiagnostics(kaKtFile)
        val tooManyArgs = errors.firstOrNull { it.key == "TOO_MANY_ARGUMENTS" } ?: return
        val typed = tooManyArgs.typed<KaFirDiagnostic.TooManyArguments>()
        assertNotNull("typed() should succeed for matching type", typed)
    }

    /** typed() returns null when the diagnostic does not match the requested type. */
    fun testTypedReturnsNullForMismatchedType() {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val errors = getDiagnostics(kaKtFile)
        val tooManyArgs = errors.firstOrNull { it.key == "TOO_MANY_ARGUMENTS" } ?: return
        // Attempting to cast a TooManyArguments diagnostic to NoElseInWhen should return null
        val typed = tooManyArgs.typed<KaFirDiagnostic.NoElseInWhen>()
        assertNull("typed() should return null for mismatched type", typed)
    }
}
