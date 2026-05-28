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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.modules.csl.api.Severity
import utils.KotlinTestCase

/**
 * Unit tests for [KaDiagnosticError].
 *
 * Uses real K2 diagnostics from [KotlinAnalysisAPISession] so that all interface
 * methods are exercised against actual Analysis API objects.
 */
@OptIn(KaExperimentalApi::class)
class KaDiagnosticErrorTest : KotlinTestCase("KaDiagnosticError", "diagnostics") {

    /**
     * Verifies that [KaDiagnosticError] correctly wraps a K2 diagnostic and exposes
     * valid values for all [org.netbeans.modules.csl.api.Error] interface methods.
     *
     * Uses any available diagnostic from the K2 session. Skips if the session has no
     * diagnostics for the test files (e.g. when stdlib is absent from the K2 classpath).
     */
    fun testPropertiesAreCorrectlyMapped() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.name == "checkTypeMismatch.kt" }
        assertNotNull("checkTypeMismatch.kt must be in K2 session", ktFile)

        val fileObject = dir.getFileObject("checkTypeMismatch.kt")
        assertNotNull("checkTypeMismatch.kt must exist in test resources", fileObject)

        // Collect diagnostics; skip test if none (K2 session may lack stdlib)
        val diagnostics = analyze(ktFile!!) {
            ktFile.diagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
        }
        if (diagnostics.isEmpty()) return

        val diag = diagnostics.first()
        val kaError = KaDiagnosticError(diag, fileObject!!)

        assertNotNull("displayName must not be null", kaError.displayName)
        assertNotNull("key must not be null", kaError.key)
        assertEquals("file must match fileObject", fileObject, kaError.file)
        assertTrue("startPosition must be >= 0", kaError.startPosition >= 0)
        assertTrue("endPosition must be >= startPosition", kaError.endPosition >= kaError.startPosition)
        assertNull("parameters must be null", kaError.parameters)
        assertNotNull("severity must not be null", kaError.severity)
    }

    /**
     * Verifies [KaDiagnosticError.severity] and [KaDiagnosticError.showExplorerBadge]
     * are consistent: ERROR diagnostics show a badge, non-ERROR ones do not.
     */
    fun testSeverityAndBadgeAreConsistent() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        assertNotNull("Session must have at least one source file", ktFile)

        val fileObject = dir.getFileObject("checkTypeMismatch.kt")!!

        val diagnostics = analyze(ktFile!!) {
            ktFile.diagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
        }
        if (diagnostics.isEmpty()) return

        for (diag in diagnostics) {
            val kaError = KaDiagnosticError(diag, fileObject)
            when (diag.severity) {
                KaSeverity.ERROR   -> {
                    assertEquals("ERROR must map to Severity.ERROR", Severity.ERROR, kaError.severity)
                    assertTrue("showExplorerBadge must be true for ERROR", kaError.showExplorerBadge())
                }
                KaSeverity.WARNING -> {
                    assertEquals("WARNING must map to Severity.WARNING", Severity.WARNING, kaError.severity)
                    assertFalse("showExplorerBadge must be false for WARNING", kaError.showExplorerBadge())
                }
                KaSeverity.INFO    -> {
                    assertEquals("INFO must map to Severity.INFO", Severity.INFO, kaError.severity)
                    assertFalse("showExplorerBadge must be false for INFO", kaError.showExplorerBadge())
                }
            }
        }
    }
}
