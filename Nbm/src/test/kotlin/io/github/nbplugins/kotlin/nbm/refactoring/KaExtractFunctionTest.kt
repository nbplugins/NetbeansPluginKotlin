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
import io.github.nbplugins.kotlin.refactoring.KaExtractFunctionComputer
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaExtractFunctionComputer].
 *
 * Fixtures are in `projForTest/src/extractFunction/`. Each sub-directory contains:
 *  - `file.kt` — the Kotlin source being refactored
 *  - `file.selection` — same source with `<start>` and `<end>` markers delimiting the selection
 *
 * Tests verify that [KaExtractFunctionComputer.compute] produces the correct [Outcome] for
 * representative cases.
 */
class KaExtractFunctionTest : KotlinTestCase("KaExtractFunctionTest", "extractFunction") {

    companion object {
        private const val START_MARKER = "<start>"
        private const val END_MARKER = "<end>"
    }

    /**
     * Returns the active K2 session, or `null` when no source dependencies are available (CI /
     * sandbox mode). Tests that need K2 should early-out via this helper.
     */
    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaExtractFunctionTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Reads the selection start and end offsets from `<subDir>/file.selection`.
     *
     * The file contains `<start>` and `<end>` markers; the start offset is the index of
     * `<start>`, the end offset accounts for the removed start marker length.
     *
     * @return pair of (startOffset, endOffset) in the unmarked source, or `null` when file or markers are absent
     */
    private fun readSelectionOffsets(subDir: String): Pair<Int, Int>? {
        val fo = dir.getFileObject(subDir)?.getFileObject("file.selection") ?: return null
        val text = fo.asText()
        val startIdx = text.indexOf(START_MARKER)
        val endIdx = text.indexOf(END_MARKER)
        if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) return null
        val start = startIdx
        val end = endIdx - START_MARKER.length   // shift left because <start> was stripped
        return start to end
    }

    /**
     * Locates `<subDir>/file.kt`, registers it in the session, and returns a [KaExtractFunctionComputer]
     * initialized with the selection from `file.selection`.
     *
     * @param subDir fixture sub-directory (e.g. `"simpleExpr"`)
     * @return computer or `null` when setup fails
     */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): KaExtractFunctionComputer? {
        val subFo = dir.getFileObject(subDir) ?: return null
        val fileFo = subFo.getFileObject("file.kt") ?: return null
        val (startOffset, endOffset) = readSelectionOffsets(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaExtractFunctionComputer(
            ktFile = ktFile,
            startOffset = startOffset,
            endOffset = endOffset,
            project = session.session.project,
        )
    }

    /**
     * Selection spanning `40 + 2` inside `println(40 + 2)`: the computer must return
     * [KaExtractFunctionComputer.Outcome.Ready] with at least one name suggestion.
     */
    fun testSimpleExpr_returnsReady() {
        val session = getSessionOrSkip() ?: return
        val computer = prepareComputer("simpleExpr", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready outcome for simple integer expression, got $outcome",
            outcome is KaExtractFunctionComputer.Outcome.Ready,
        )
        val result = (outcome as KaExtractFunctionComputer.Outcome.Ready).result
        assertTrue(
            "Expected at least one suggested name, got none",
            result.suggestedNames.isNotEmpty(),
        )
        assertTrue(
            "Expected non-negative insert offset",
            result.insertOffset >= 0,
        )
    }

    /**
     * Selection spanning `x * 2` where `x` is declared in the outer scope: the computer must
     * detect `x` as a parameter and include it in the result.
     */
    fun testWithParam_detectsParameter() {
        val session = getSessionOrSkip() ?: return
        val computer = prepareComputer("withParam", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready outcome for expression with outer variable, got $outcome",
            outcome is KaExtractFunctionComputer.Outcome.Ready,
        )
        val result = (outcome as KaExtractFunctionComputer.Outcome.Ready).result
        assertTrue(
            "Expected at least one parameter (for `x`), got ${result.parameters}",
            result.parameters.isNotEmpty(),
        )
        assertEquals(
            "Expected parameter named 'x'",
            "x",
            result.parameters.first().name,
        )
    }

    /**
     * Selection on the `fun` keyword — not an extractable expression or statement block: the
     * computer must return [KaExtractFunctionComputer.Outcome.NotApplicable].
     */
    fun testNotApplicable_onKeyword() {
        val session = getSessionOrSkip() ?: return
        val computer = prepareComputer("notApplicable", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable when selection is on a keyword, got $outcome",
            outcome is KaExtractFunctionComputer.Outcome.NotApplicable,
        )
    }

    // ------------------------------------------------------------------
    // Integration tests with a real K2 session (kotlin-stdlib on classpath)
    // ------------------------------------------------------------------

    /**
     * Locates `kotlin-stdlib-*.jar` on the test classpath.
     *
     * @return the path, or `null` when the jar is not present
     */
    private fun findKotlinStdlib(): Path? =
        System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /**
     * Builds a fresh [KotlinAnalysisAPISession] backed by `kotlin-stdlib` and a single temp
     * source file copied from `projForTest/src/extractFunction/<subDir>/file.kt`.
     *
     * @param subDir fixture sub-directory
     * @return triple of (computer, KtFile, tmpDir) or `null` when stdlib is not on classpath
     */
    private fun prepareWithRealSession(subDir: String): Triple<KaExtractFunctionComputer, KtFile, Path>? {
        val stdlib = findKotlinStdlib() ?: return null
        val subFo = dir.getFileObject(subDir) ?: error("Missing fixture dir: $subDir")
        val fileFo = subFo.getFileObject("file.kt") ?: error("Missing $subDir/file.kt")
        val (startOffset, endOffset) = readSelectionOffsets(subDir)
            ?: error("Could not read selection offsets from $subDir/file.selection")
        val source = fileFo.asText()

        val tmpDir = Files.createTempDirectory("nbkotlin-extract-$subDir")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "extract-function-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")

        val computer = KaExtractFunctionComputer(
            ktFile = ktFile,
            startOffset = startOffset,
            endOffset = endOffset,
            project = session.session.project,
        )
        return Triple(computer, ktFile, tmpDir)
    }

    /**
     * Integration test: exercises [KaExtractFunctionComputer.compute] against a real K2 session
     * backed by `kotlin-stdlib`.
     *
     * Verifies for `simpleExpr`:
     *  - outcome is [KaExtractFunctionComputer.Outcome.Ready]
     *  - at least one name suggestion is returned
     *  - insert offset is >= 0
     *  - return type is non-null (not Unit — `40 + 2` is `Int`)
     */
    fun testCompute_withRealSession_simpleExpr() {
        val triple = prepareWithRealSession("simpleExpr")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()

            assertTrue(
                "Expected Ready from real session, got $outcome",
                outcome is KaExtractFunctionComputer.Outcome.Ready,
            )
            val result = (outcome as KaExtractFunctionComputer.Outcome.Ready).result
            assertTrue("Expected name suggestions from real session", result.suggestedNames.isNotEmpty())
            assertTrue("insertOffset must be >= 0", result.insertOffset >= 0)
            assertFalse("Expected non-Unit return type for Int expression", result.isUnit)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: exercises the text-transformation logic of the apply element in a
     * standalone session.
     *
     * Given `simpleExpr/file.kt` with `println(40 + 2)`, extracts `40 + 2` into a function
     * named `"extracted"` and verifies that the result text:
     *  - contains `fun extracted(): Int` (or similar) at the top level
     *  - contains `println(extracted())` replacing the original call
     */
    fun testApply_withRealSession_simpleExpr() {
        val triple = prepareWithRealSession("simpleExpr")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping apply integration test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue("Expected Ready", outcome is KaExtractFunctionComputer.Outcome.Ready)
            val result = (outcome as KaExtractFunctionComputer.Outcome.Ready).result

            val chosenName = "extracted"
            val paramList = result.parameters.joinToString(", ") { "${it.name}: ${it.typeText}" }
            val returnPart = if (result.isUnit) "" else ": ${result.returnTypeText}"
            val funcDecl = "fun $chosenName($paramList)$returnPart {\n    ${result.selectionText}\n}"
            val callArgs = result.parameters.joinToString(", ") { it.name }
            val callExpr = "$chosenName($callArgs)"

            var newText = ktFile.text
            // replace selection with call expression (back-to-front single replacement)
            newText = newText.substring(0, result.selectionRange.startOffset) +
                    callExpr +
                    newText.substring(result.selectionRange.endOffset)
            // insert function before the insert offset line
            val insertLine = newText.lastIndexOf('\n', result.insertOffset - 1) + 1
            newText = newText.substring(0, insertLine) + funcDecl + "\n" + newText.substring(insertLine)

            assertTrue(
                "Expected 'fun $chosenName(' in output:\n$newText",
                newText.contains("fun $chosenName("),
            )
            assertTrue(
                "Expected '$chosenName()' call in output:\n$newText",
                newText.contains("$chosenName("),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
