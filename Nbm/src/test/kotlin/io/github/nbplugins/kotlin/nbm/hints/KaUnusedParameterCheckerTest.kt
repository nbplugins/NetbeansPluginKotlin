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
package io.github.nbplugins.kotlin.nbm.hints

import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaUnusedParameterChecker].
 *
 * Uses the `diagnostics` test resource directory which contains [parameterIsNeverUsed.kt]
 * (a private function with an unused parameter) and [checkNoTypeMismatch.kt] (a clean file).
 */
class KaUnusedParameterCheckerTest : KotlinTestCase("KaUnusedParameterChecker", "diagnostics") {

    override fun tearDown() {
        KotlinAnalysisAPISession.disposeAll()
        super.tearDown()
    }

    private fun getKaKtFileOrSkip(path: String): KtFile? {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        if (!wrapper.hasDependencies) {
            println("KaUnusedParameterCheckerTest: skipping — no K2 dependencies")
            return null
        }
        return wrapper.getKtFileForPath(path)
    }

    /**
     * Verifies that [KaUnusedParameterChecker] reports exactly one hint for
     * [parameterIsNeverUsed.kt], which declares a private function with one unused parameter.
     */
    fun testUnusedParameterDetected() {
        val file = dir.getFileObject("parameterIsNeverUsed.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return

        val ktFile = KotlinPsiManager.getParsedFile(file)!!
        val parserResult = KotlinParserResult(null, ktFile, file, project, kaKtFile)

        val hints = KaUnusedParameterChecker(parserResult, kaKtFile).getUnusedParameterHints()
        assertEquals("Expected exactly 1 unused parameter hint", 1, hints.size)
        assertTrue(
            "Hint description must mention the parameter name 'str'",
            hints[0].description.contains("str")
        )
    }

    /**
     * Verifies that [KaUnusedParameterChecker] produces no hints for a file with no
     * eligible functions ([checkNoTypeMismatch.kt]).
     */
    fun testCleanFileProducesNoHints() {
        val file = dir.getFileObject("checkNoTypeMismatch.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return

        val ktFile = KotlinPsiManager.getParsedFile(file)!!
        val parserResult = KotlinParserResult(null, ktFile, file, project, kaKtFile)

        val hints = KaUnusedParameterChecker(parserResult, kaKtFile).getUnusedParameterHints()
        assertTrue("Expected no hints for a clean file", hints.isEmpty())
    }
}
