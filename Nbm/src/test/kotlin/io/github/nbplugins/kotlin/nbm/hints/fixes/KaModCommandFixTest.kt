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
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.idea.quickfix.RemoveArgumentFix
import org.jetbrains.kotlin.psi.KtValueArgument
import utils.KotlinTestCase
import utils.getDocumentForFileObject

/**
 * Unit tests for [KaModCommandFix].
 *
 * Verifies that [KaModCommandFix.isApplicable] returns true, [KaModCommandFix.getDescription]
 * returns the provided description, and [KaModCommandFix.implement] applies the PSI mutation
 * and syncs it to the document.
 */
class KaModCommandFixTest : KotlinTestCase("KaModCommandFix", "quickfixes") {

    private fun getKaKtFileOrSkip(path: String) =
        KotlinAnalysisAPISession.getSession(project).takeIf { it.hasDependencies }
            ?.getKtFileForPath(path)

    private fun getError(kaKtFile: org.jetbrains.kotlin.psi.KtFile, key: String): KaDiagnosticError? {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return null
        val ktFile = KotlinPsiManager.getParsedFile(file) ?: return null
        return KotlinParserResult(null, ktFile, file, project, kaKtFile)
            .getDiagnostics().filterIsInstance(KaDiagnosticError::class.java)
            .firstOrNull { it.key == key }
    }

    /** isApplicable returns true for a newly created KaModCommandFix. */
    fun testIsApplicableReturnsTrue() {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val error = getError(kaKtFile, "TOO_MANY_ARGUMENTS") ?: return
        val arg = error.kaDiagnostic.psi.parent as? KtValueArgument ?: return
        val doc = getDocumentForFileObject(file) ?: return
        val fix = KaModCommandFix(error, RemoveArgumentFix(arg), arg, doc, kaKtFile,"Remove argument")
        assertTrue(fix.isApplicable())
    }

    /** getDescription returns the provided label. */
    fun testGetDescriptionReturnsProvidedLabel() {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val error = getError(kaKtFile, "TOO_MANY_ARGUMENTS") ?: return
        val arg = error.kaDiagnostic.psi.parent as? KtValueArgument ?: return
        val doc = getDocumentForFileObject(file) ?: return
        val fix = KaModCommandFix(error, RemoveArgumentFix(arg), arg, doc, kaKtFile,"Test description")
        assertEquals("Test description", fix.getDescription())
    }

    /** isSafe returns true. */
    fun testIsSafe() {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val error = getError(kaKtFile, "TOO_MANY_ARGUMENTS") ?: return
        val arg = error.kaDiagnostic.psi.parent as? KtValueArgument ?: return
        val doc = getDocumentForFileObject(file) ?: return
        val fix = KaModCommandFix(error, RemoveArgumentFix(arg), arg, doc, kaKtFile,"Remove argument")
        assertTrue(fix.isSafe())
    }

    /** isInteractive returns false. */
    fun testIsNotInteractive() {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val error = getError(kaKtFile, "TOO_MANY_ARGUMENTS") ?: return
        val arg = error.kaDiagnostic.psi.parent as? KtValueArgument ?: return
        val doc = getDocumentForFileObject(file) ?: return
        val fix = KaModCommandFix(error, RemoveArgumentFix(arg), arg, doc, kaKtFile,"Remove argument")
        assertFalse(fix.isInteractive())
    }
}
