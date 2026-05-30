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
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import utils.getDocumentForFileObject

/**
 * Unit tests for [KaRemoveNoConstructorFix].
 *
 * Verifies that [KaRemoveNoConstructorFix.isApplicable] returns true for
 * NO_CONSTRUCTOR diagnostics and false for unrelated diagnostics.
 * Description: removes a constructor invocation from a supertype without a constructor.
 */
class KaRemoveNoConstructorFixTest : KotlinTestCase("KaRemoveNoConstructorFix", "quickfixes") {

    private fun getKaKtFileOrSkip(path: String): KtFile? =
        KotlinAnalysisAPISession.getSession(project).takeIf { it.hasDependencies }
            ?.getKtFileForPath(path)

    private fun getErrors(kaKtFile: KtFile): List<KaDiagnosticError> {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return emptyList()
        val ktFile = KotlinPsiManager.getParsedFile(file) ?: return emptyList()
        return KotlinParserResult(null, ktFile, file, project, kaKtFile)
            .getDiagnostics().filterIsInstance(KaDiagnosticError::class.java)
    }

    /** isApplicable returns true when the diagnostic key matches. */
    fun testIsApplicableForMatchingKey() {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val doc = getDocumentForFileObject(file) ?: return
        val errors = getErrors(kaKtFile)
        val error = errors.firstOrNull { it.key == "NO_CONSTRUCTOR" } ?: return
        assertTrue(KaRemoveNoConstructorFix(error, doc, kaKtFile).isApplicable())
    }

    /** isApplicable returns false for a different diagnostic key. */
    fun testIsNotApplicableForOtherKey() {
        val file = dir.getFileObject("modCommandFixes.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val doc = getDocumentForFileObject(file) ?: return
        val errors = getErrors(kaKtFile)
        val error = errors.firstOrNull { it.key != "NO_CONSTRUCTOR" } ?: return
        assertFalse(KaRemoveNoConstructorFix(error, doc, kaKtFile).isApplicable())
    }
}
