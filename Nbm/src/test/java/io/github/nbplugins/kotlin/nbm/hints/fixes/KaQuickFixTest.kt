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
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParser
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaQuickFix].
 *
 * Verifies the base interface contract: [KaQuickFix.isSafe] returns true and
 * [KaQuickFix.isInteractive] returns false for any concrete implementation.
 *
 * Uses [KaImplementMembersFix] as a representative concrete implementation.
 */
class KaQuickFixTest : KotlinTestCase("KaQuickFix", "quickfixes") {

    private fun getKaKtFileOrSkip(path: String): KtFile? {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        if (!wrapper.hasDependencies) {
            println("KaQuickFixTest: skipping — no K2 dependencies")
            return null
        }
        return wrapper.getKtFileForPath(path)
    }

    private fun getAnyK2Error(kaKtFile: KtFile): KaDiagnosticError? {
        val file = dir.getFileObject("implementMembers.kt") ?: return null
        val ktFile = KotlinPsiManager.getParsedFile(file) ?: return null
        val resultWithProvider = KotlinParser.getAnalysisResult(ktFile, project) ?: return null
        val parserResult = KotlinParserResult(null, resultWithProvider, ktFile, file, project, kaKtFile)
        return parserResult.getDiagnostics().filterIsInstance(KaDiagnosticError::class.java).firstOrNull()
    }

    /** isSafe must return true. */
    fun testIsSafe() {
        val file = dir.getFileObject("implementMembers.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val error = getAnyK2Error(kaKtFile) ?: return

        val fix = KaImplementMembersFix(error, kaKtFile)
        assertTrue(fix.isSafe())
    }

    /** isInteractive must return false. */
    fun testIsNotInteractive() {
        val file = dir.getFileObject("implementMembers.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val error = getAnyK2Error(kaKtFile) ?: return

        val fix = KaImplementMembersFix(error, kaKtFile)
        assertFalse(fix.isInteractive())
    }
}
