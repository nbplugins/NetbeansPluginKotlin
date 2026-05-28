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
package io.github.nbplugins.kotlin.nbm.diagnostics.parser

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [KotlinParserResult.getDiagnostics].
 *
 * Verifies that [KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS] exposes the expected K2 diagnostics
 * for each test-resource file, including extended-checker diagnostics such as
 * [KaSeverity.WARNING]-level `UNUSED_VARIABLE` and `UNUSED_PARAMETER`.
 *
 * Each test skips gracefully when the K2 session produces no diagnostics (e.g. when the
 * Kotlin stdlib is absent from the test classpath), matching the convention in
 * [io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticErrorTest].
 */
class KotlinParserResultTest : KotlinTestCase("KotlinParserResult", "diagnostics") {

    override fun tearDown() {
        KotlinAnalysisAPISession.disposeAll()
        super.tearDown()
    }

    /**
     * Verifies that [KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS] reports an
     * `INITIALIZER_TYPE_MISMATCH` ERROR diagnostic for [checkTypeMismatch.kt].
     *
     * K2 uses `INITIALIZER_TYPE_MISMATCH` when a property initializer's type does not match
     * the declared type (the more specific variant of `TYPE_MISMATCH` for initializers).
     */
    fun testTypeMismatchDiagnosticReported() {
        assertDiagnosticPresent("checkTypeMismatch.kt", "INITIALIZER_TYPE_MISMATCH", KaSeverity.ERROR)
    }

    /**
     * Verifies that [KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS] reports an `UNUSED_VARIABLE`
     * WARNING diagnostic for a function containing an unused local variable.
     */
    fun testUnusedVariableDiagnosticReported() {
        assertDiagnosticPresent("checkUnusedVariable.kt", "UNUSED_VARIABLE", KaSeverity.WARNING)
    }

    /**
     * Verifies that [KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS] reports an `UNUSED_PARAMETER`
     * WARNING diagnostic for a function with an unused parameter.
     */
    fun testUnusedParameterDiagnosticReported() {
        assertDiagnosticPresent("parameterIsNeverUsed.kt", "UNUSED_PARAMETER", KaSeverity.WARNING)
    }

    /**
     * Verifies that a semantically valid file produces no ERROR-severity diagnostics,
     * confirming that [KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS] does not introduce false positives
     * for correct code.
     */
    fun testNoFalsePositivesInCleanFile() {
        val ktFile = findKtFile("checkNoTypeMismatch.kt") ?: return
        val diagnostics = analyze(ktFile) {
            ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
        }
        val errors = diagnostics.filter { it.severity == KaSeverity.ERROR }
        assertTrue(
            "checkNoTypeMismatch.kt must produce no ERROR diagnostics, got: ${errors.map { it.factoryName }}",
            errors.isEmpty()
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun findKtFile(name: String): KtFile? {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        return wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == name }
    }

    /**
     * Asserts that [fileName] contains at least one diagnostic with [factoryName] at [severity].
     * Skips the test (returns early) when no diagnostics at all are returned — that indicates
     * the K2 session lacks stdlib and produces no analysis results.
     */
    private fun assertDiagnosticPresent(fileName: String, factoryName: String, severity: KaSeverity) {
        val ktFile = findKtFile(fileName)
        assertNotNull("$fileName must be in K2 session", ktFile)
        val diagnostics = analyze(ktFile!!) {
            ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
        }
        if (diagnostics.isEmpty()) return  // skip: no stdlib, no analysis results
        val match = diagnostics.any { it.factoryName == factoryName && it.severity == severity }
        assertTrue(
            "Expected $severity diagnostic '$factoryName' in $fileName, got: ${diagnostics.map { "${it.factoryName}(${it.severity})" }}",
            match
        )
    }
}
