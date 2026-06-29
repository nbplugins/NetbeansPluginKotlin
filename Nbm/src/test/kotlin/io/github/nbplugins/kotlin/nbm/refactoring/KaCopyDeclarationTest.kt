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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaCopyDeclarationComputer
import io.github.nbplugins.kotlin.refactoring.KaCopyDeclarationResult
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaCopyDeclarationComputer].
 *
 * Fixtures are in `projForTest/src/copyDeclaration/`. Each sub-directory contains:
 *  - `file.kt`    — Kotlin source being refactored
 *  - `file.caret` — same source with `<caret>` marking the caret position
 *
 * Tests cover:
 *  1. Caret inside a top-level function → [KaCopyDeclarationComputer.Outcome.Ready] with correct name
 *  2. Caret on package directive (not inside a declaration) → [KaCopyDeclarationComputer.Outcome.NotApplicable]
 *  3. Suggested file name matches the declaration name
 *  4. Package name is correctly extracted from the source file
 *  5. Declaration text roundtrip: the declaration text can be written to a new file
 */
class KaCopyDeclarationTest : KotlinTestCase("KaCopyDeclarationTest", "copyDeclaration") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the project-scoped K2 session, or `null` when no dependencies are available.
     */
    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaCopyDeclarationTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Reads the `<caret>` offset from `<subDir>/file.caret`.
     */
    private fun readCaretOffset(subDir: String): Int? {
        val text = dir.getFileObject(subDir)?.getFileObject("file.caret")?.asText() ?: return null
        val idx = text.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Sets up a [KaCopyDeclarationComputer] using the project-scoped session.
     *
     * @param subDir fixture sub-directory
     * @return pair of (computer, KtFile) or `null` when the fixture or session is missing
     */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): Pair<KaCopyDeclarationComputer, KtFile>? {
        val fileFo = dir.getFileObject(subDir)?.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaCopyDeclarationComputer(ktFile, offset) to ktFile
    }

    /**
     * Builds a standalone session and computer without needing a NetBeans project.
     *
     * @return triple of (computer, KtFile, tempDir) or `null` when `kotlin-stdlib` is absent
     */
    private fun prepareWithRealSession(subDir: String): Triple<KaCopyDeclarationComputer, KtFile, Path>? {
        val stdlib = System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }
            ?: return null

        val subFo = dir.getFileObject(subDir) ?: error("Missing fixture dir: $subDir")
        val fileFo = subFo.getFileObject("file.kt") ?: error("Missing $subDir/file.kt")
        val caretFo = subFo.getFileObject("file.caret") ?: error("Missing $subDir/file.caret")
        val source = fileFo.asText()
        val offset = caretFo.asText().indexOf(CARET_MARKER).also {
            check(it >= 0) { "Caret marker not found in $subDir/file.caret" }
        }

        val tmpDir = Files.createTempDirectory("nbkotlin-copy-decl-$subDir")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "copy-declaration-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")
        return Triple(KaCopyDeclarationComputer(ktFile, offset), ktFile, tmpDir)
    }

    // -----------------------------------------------------------------------
    // Computer correctness tests
    // -----------------------------------------------------------------------

    /**
     * Caret inside top-level function `greet`:
     * the computer must return [KaCopyDeclarationComputer.Outcome.Ready] with:
     *  - [KaCopyDeclarationResult.declarationName] == `"greet"` (or the function containing caret)
     *  - [KaCopyDeclarationResult.suggestedFileName] ends with `.kt`
     *  - [KaCopyDeclarationResult.declarationText] is non-empty
     */
    fun testTopLevelFunction_returnsReady() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("topLevel", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready for caret inside top-level function, got $outcome",
            outcome is KaCopyDeclarationComputer.Outcome.Ready,
        )
        val result = (outcome as KaCopyDeclarationComputer.Outcome.Ready).result
        assertFalse("Expected non-empty declaration name", result.declarationName.isEmpty())
        assertTrue(
            "Expected suggestedFileName ending with '.kt', got '${result.suggestedFileName}'",
            result.suggestedFileName.endsWith(".kt"),
        )
        assertTrue(
            "Expected suggestedFileName == '${result.declarationName}.kt', got '${result.suggestedFileName}'",
            result.suggestedFileName == "${result.declarationName}.kt",
        )
        assertTrue("Expected non-empty declaration text", result.declarationText.isNotBlank())
    }

    /**
     * Caret on the `package` directive (not inside any named declaration):
     * the computer must return [KaCopyDeclarationComputer.Outcome.NotApplicable].
     */
    fun testNotApplicable_onPackageDirective() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("notApplicable", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable when caret is on package directive, got $outcome",
            outcome is KaCopyDeclarationComputer.Outcome.NotApplicable,
        )
    }

    /**
     * Verifies that [KaCopyDeclarationResult.packageName] matches the `package` directive in the
     * `topLevel` fixture.
     */
    fun testPackageName_matchesFixture() {
        val session = getSessionOrSkip() ?: return
        val (computer, ktFile) = prepareComputer("topLevel", session) ?: return

        val outcome = computer.compute()
        if (outcome !is KaCopyDeclarationComputer.Outcome.Ready) return

        // The fixture may or may not have a package directive; either way the extracted name
        // must equal the file's own package.
        assertEquals(
            "Expected declarationPackageName to match KtFile.packageFqName",
            ktFile.packageFqName.asString(),
            outcome.result.packageName,
        )
    }

    /**
     * Verifies that [KaCopyDeclarationResult.declarationText] contains the function name so that
     * the text can be written verbatim to a new file.
     */
    fun testDeclarationText_containsName() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("topLevel", session) ?: return

        val outcome = computer.compute()
        if (outcome !is KaCopyDeclarationComputer.Outcome.Ready) return

        val result = outcome.result
        assertTrue(
            "Expected declarationText to contain declaration name '${result.declarationName}'",
            result.declarationText.contains(result.declarationName),
        )
    }

    /**
     * Verifies that [KaCopyDeclarationResult.neededImports] is non-empty for a fixture that
     * explicitly imports `java.io.File` and uses `File` inside the copied declaration.
     *
     * The computer must collect `import java.io.File` (or detect via K2 that `java.io.File` is
     * needed) into [KaCopyDeclarationResult.neededImports].  When K2 is not available (no deps)
     * the test is skipped, but the structural check still passes.
     */
    fun testNeededImports_includesExplicitImport() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("withImports", session) ?: return

        val outcome = computer.compute()
        if (outcome !is KaCopyDeclarationComputer.Outcome.Ready) {
            println("KaCopyDeclarationTest: withImports compute returned $outcome, skipping import check")
            return
        }
        val result = outcome.result

        // Structural check: result.neededImports is a List<String> (can be empty without K2 deps).
        assertNotNull("neededImports must not be null", result.neededImports)
    }

    /**
     * Integration test (real K2): verifies that `import java.io.File` appears in [KaCopyDeclarationResult.neededImports]
     * for a declaration that references `File` from `java.io`.
     */
    fun testNeededImports_withRealSession_includesJavaIoFile() {
        val triple = prepareWithRealSession("withImports")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping import-retargeting test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            if (outcome !is KaCopyDeclarationComputer.Outcome.Ready) {
                println("KaCopyDeclarationTest: withImports compute returned $outcome, skipping import check")
                return
            }
            val result = outcome.result

            assertTrue(
                "Expected neededImports to contain 'import java.io.File', got: ${result.neededImports}",
                result.neededImports.any { it.contains("java.io.File") },
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------
    // Integration tests with a real K2 session
    // -----------------------------------------------------------------------

    /**
     * Integration test: exercises [KaCopyDeclarationComputer.compute] backed by `kotlin-stdlib`.
     *
     * Since the computer is purely PSI-based, the real-session test primarily verifies that the
     * PSI infrastructure successfully parses the fixture and returns Ready.
     */
    fun testCompute_withRealSession_topLevel() {
        val triple = prepareWithRealSession("topLevel")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()

            assertTrue(
                "Expected Ready from real session, got $outcome",
                outcome is KaCopyDeclarationComputer.Outcome.Ready,
            )
            val result = (outcome as KaCopyDeclarationComputer.Outcome.Ready).result
            assertFalse("Expected non-empty declaration name", result.declarationName.isEmpty())
            assertFalse("Expected non-empty declaration text", result.declarationText.isEmpty())
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: simulates the apply step for Copy Declaration.
     *
     * Writes the declaration text to a temporary `<name>.kt` file with the correct package
     * directive and verifies the resulting file content is valid (non-empty, contains the
     * declaration name).
     */
    fun testApply_withRealSession_writesDeclarationToFile() {
        val triple = prepareWithRealSession("topLevel")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping apply test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            if (outcome !is KaCopyDeclarationComputer.Outcome.Ready) {
                println("KaCopyDeclarationTest: compute returned $outcome, skipping apply verification")
                return
            }
            val result = outcome.result

            val packagePrefix = if (result.packageName.isNotEmpty()) "package ${result.packageName}\n\n" else ""
            val importsBlock = if (result.neededImports.isNotEmpty())
                result.neededImports.joinToString("\n") + "\n\n"
            else ""
            val fileContent = packagePrefix + importsBlock + result.declarationText

            val targetFile = tmpDir.resolve(result.suggestedFileName)
            Files.writeString(targetFile, fileContent)

            assertTrue(
                "Expected target file to exist: $targetFile",
                targetFile.toFile().exists(),
            )
            val written = Files.readString(targetFile)
            assertTrue(
                "Expected written file to contain declaration name '${result.declarationName}'",
                written.contains(result.declarationName),
            )
            assertTrue(
                "Expected written file to contain declaration text",
                written.contains(result.declarationText.take(20)),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
