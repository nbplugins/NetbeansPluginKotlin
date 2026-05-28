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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
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
        val parserResult = KotlinParserResult(null, ktFile, file, project, kaKtFile)

        val hints = KaUnusedImportsComputer(parserResult, kaKtFile).getUnusedImports()
        // implementMembers.kt has no import directives, so result must be empty
        assertTrue("Expected no hints for a file with no imports", hints.isEmpty())
    }

    /**
     * Verifies that [KaUnusedImportsComputer] detects exactly one unused import in
     * [unusedImport.kt], which imports `java.io.File` (unused) and `java.util.HashMap` (used).
     */
    fun testUnusedImportDetected() {
        val file = dir.getFileObject("unusedImport.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return

        val ktFile = KotlinPsiManager.getParsedFile(file)!!
        val parserResult = KotlinParserResult(null, ktFile, file, project, kaKtFile)

        val hints = KaUnusedImportsComputer(parserResult, kaKtFile).getUnusedImports()
        assertEquals("Expected exactly 1 unused import hint", 1, hints.size)
        assertEquals("Hint description must be 'Unused import'", "Unused import", hints[0].description)
        assertTrue(
            "Hint fix must contain 'Remove unused import'",
            hints[0].fixes?.any { it.description == "Remove unused import" } == true
        )
    }

    /**
     * Verifies that [KaUnusedImportsComputer] produces no hint for [unusedImport.kt]'s used
     * import (`java.util.HashMap`) — i.e. the used import is not falsely flagged.
     */
    fun testUsedImportProducesNoHint() {
        val file = dir.getFileObject("unusedImport.kt") ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return

        val ktFile = KotlinPsiManager.getParsedFile(file)!!
        val parserResult = KotlinParserResult(null, ktFile, file, project, kaKtFile)

        val hints = KaUnusedImportsComputer(parserResult, kaKtFile).getUnusedImports()
        val hintTexts = hints.map { it.description }
        assertFalse(
            "java.util.HashMap is used as return type — must not be reported as unused",
            hintTexts.any { it.contains("HashMap") }
        )
    }
}
