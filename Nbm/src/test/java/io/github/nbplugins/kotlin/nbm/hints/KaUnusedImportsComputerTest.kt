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
package io.github.nbplugins.kotlin.nbm.hints

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParser
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaUnusedImportsComputer].
 *
 * Uses the existing `unusedImports` test fixture from the `quickfixes` resource directory.
 */
class KaUnusedImportsComputerTest : KotlinTestCase("KaUnusedImportsComputer", "quickfixes") {

    private fun getKaKtFileOrSkip(path: String): KtFile? {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        if (!wrapper.hasDependencies) {
            println("KaUnusedImportsComputerTest: skipping — no K2 dependencies")
            return null
        }
        return wrapper.getKtFileForPath(path)
    }

    /**
     * A file with no imports should produce no unused import hints.
     */
    fun testNoImportsProducesNoHints() {
        val file = dir.getFileObject("implementMembers.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return

        val ktFile = KotlinPsiManager.getParsedFile(file)!!
        val analysisResult = KotlinParser.getAnalysisResult(ktFile, project)
        val parserResult = KotlinParserResult(
            null,
            analysisResult,
            ktFile,
            file,
            project,
            kaKtFile
        )

        val hints = KaUnusedImportsComputer(parserResult, kaKtFile).getUnusedImports()
        // implementMembers.kt has no import directives, so result must be empty
        assertTrue("Expected no hints for a file with no imports", hints.isEmpty())
    }
}
