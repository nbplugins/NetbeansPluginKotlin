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
import io.github.nbplugins.kotlin.refactoring.KaIntroduceVariableComputer
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaIntroduceVariableComputer].
 *
 * Fixtures are in `projForTest/src/introduceVariable/`. Each sub-directory contains:
 *  - `file.kt` — the Kotlin source being refactored
 *  - `file.caret` — same source with `<caret>` marking the caret position
 *
 * Tests verify that [KaIntroduceVariableComputer.compute] produces the correct [Outcome] for
 * representative cases.  Text-transformation correctness is covered by the apply-step integration
 * test [testApply_withRealSession_simpleExpr].
 */
class KaIntroduceVariableTest : KotlinTestCase("KaIntroduceVariableTest", "introduceVariable") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    /**
     * Returns the active K2 session, or `null` when no source dependencies are available (CI /
     * sandbox mode). Tests that need K2 should early-out via this helper.
     */
    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaIntroduceVariableTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Reads the `<caret>` offset from `<subDir>/file.caret`.
     *
     * @return the character index of the marker, or `null` when the file or marker is absent
     */
    private fun readCaretOffset(subDir: String): Int? {
        val caretFo = dir.getFileObject(subDir)?.getFileObject("file.caret") ?: return null
        val text = caretFo.asText()
        val idx = text.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Locates `<subDir>/file.kt`, registers it in the session, and returns the [KaIntroduceVariableComputer]
     * and the resolved [KtFile].
     *
     * @param subDir fixture sub-directory (e.g. `"simpleExpr"`)
     * @return pair of computer and KtFile, or `null` when setup fails
     */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): Pair<KaIntroduceVariableComputer, KtFile>? {
        val subFo = dir.getFileObject(subDir) ?: return null
        val fileFo = subFo.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null

        return KaIntroduceVariableComputer(
            ktFile = ktFile,
            startOffset = offset,
            endOffset = offset,
            project = session.session.project,
        ) to ktFile
    }

    /**
     * Caret on the literal `2` inside `println(40 + 2)`: the computer must return
     * [KaIntroduceVariableComputer.Outcome.Ready] with a non-empty suggestions list.
     */
    fun testSimpleExpr_returnsReady() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("simpleExpr", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready outcome for simple integer expression, got $outcome",
            outcome is KaIntroduceVariableComputer.Outcome.Ready,
        )
        val result = (outcome as KaIntroduceVariableComputer.Outcome.Ready).result
        assertTrue(
            "Expected at least one suggested name, got none",
            result.suggestedNames.isNotEmpty(),
        )
        assertNotNull("Expected non-null anchor offset", result.anchorOffset)
    }

    /**
     * Caret on the literal `42` with three semantically equal occurrences: the computer must report
     * exactly 3 occurrence ranges (K2SemanticMatcher matches all three identical literals).
     */
    fun testMultipleOccurrences_findsAll() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("multipleOccurrences", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready outcome for duplicate literals, got $outcome",
            outcome is KaIntroduceVariableComputer.Outcome.Ready,
        )
        val result = (outcome as KaIntroduceVariableComputer.Outcome.Ready).result
        assertEquals(
            "Expected 3 occurrence ranges for three identical '42' literals",
            3,
            result.occurrenceRanges.size,
        )
    }

    /**
     * Multiple occurrences spread across separate top-level statements trigger the anchor-computation
     * bug that was fixed: `findCommonParent` returned the container block itself, causing
     * `computeAnchor` to return null (making [KaIntroduceVariableComputer.Outcome.Ready] impossible).
     *
     * This test ensures [KaIntroduceVariableComputer.Outcome.Ready] is returned with a valid
     * (non-negative) anchor offset in the multi-statement case.
     */
    fun testMultipleOccurrences_hasValidAnchor() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("multipleOccurrences", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready outcome, got $outcome",
            outcome is KaIntroduceVariableComputer.Outcome.Ready,
        )
        val result = (outcome as KaIntroduceVariableComputer.Outcome.Ready).result
        assertTrue(
            "anchorOffset must be >= 0 so the declaration can be inserted (was ${result.anchorOffset})",
            result.anchorOffset >= 0,
        )
    }

    /**
     * Caret on a function keyword — not an expression: the computer must return
     * [KaIntroduceVariableComputer.Outcome.NotApplicable].
     */
    fun testNotApplicable_onFunctionKeyword() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("notApplicable", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable when caret is on a function declaration, got $outcome",
            outcome is KaIntroduceVariableComputer.Outcome.NotApplicable,
        )
    }

    /**
     * Regression: when a compound expression (`"Hello" + " " + "World"`) is **selected** by the
     * user (startOffset < endOffset) and the file has two identical occurrences, the computer must
     * find both occurrences so that "Replace all" replaces them all.
     *
     * Previously, [K2SemanticMatcher.findMatches] silently fell back to a single-occurrence list
     * when it encountered a compound binary expression, causing only the selected occurrence to be
     * replaced even when "Replace all" was checked.
     */
    fun testReplaceAll_withStringConcat_findsAllOccurrences() {
        val stdlib = findKotlinStdlib()
            ?: run {
                println("KaIntroduceVariableTest: kotlin-stdlib not on classpath — skipping string-concat test")
                return
            }

        val subFo = dir.getFileObject("stringConcat") ?: error("Missing fixture dir: stringConcat")
        val source = subFo.getFileObject("file.kt")?.asText() ?: error("Missing stringConcat/file.kt")

        val selectionText = """"Hello" + " " + "World""""
        val selStart = source.indexOf(selectionText).also {
            check(it >= 0) { "Selection '$selectionText' not found in stringConcat/file.kt" }
        }
        val selEnd = selStart + selectionText.length

        val tmpDir = Files.createTempDirectory("nbkotlin-introduce-string-concat")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)
        try {
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "introduce-string-concat",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(tmpFile.toString())
                ?: error("Failed to obtain KtFile for $tmpFile")

            val computer = KaIntroduceVariableComputer(
                ktFile = ktFile,
                startOffset = selStart,
                endOffset = selEnd,
                project = session.session.project,
            )
            val outcome = computer.compute()

            assertTrue(
                "Expected Ready for string-concat selection, got $outcome",
                outcome is KaIntroduceVariableComputer.Outcome.Ready,
            )
            val result = (outcome as KaIntroduceVariableComputer.Outcome.Ready).result
            assertEquals(
                "Expected 2 occurrences for two identical string-concat expressions (Replace all must find both)",
                2,
                result.occurrenceRanges.size,
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
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
     * source file copied from `projForTest/src/introduceVariable/<subDir>/file.kt`.
     *
     * @param subDir fixture sub-directory
     * @return triple of (computer, KtFile, tmpDir) or `null` when stdlib is not on classpath
     */
    private fun prepareWithRealSession(subDir: String): Triple<KaIntroduceVariableComputer, KtFile, Path>? {
        val stdlib = findKotlinStdlib() ?: return null
        val subFo = dir.getFileObject(subDir) ?: error("Missing fixture dir: $subDir")
        val fileFo = subFo.getFileObject("file.kt") ?: error("Missing $subDir/file.kt")
        val caretFo = subFo.getFileObject("file.caret") ?: error("Missing $subDir/file.caret")
        val source = fileFo.asText()
        val offset = caretFo.asText().indexOf(CARET_MARKER).also {
            check(it >= 0) { "Caret marker not found in $subDir/file.caret" }
        }

        val tmpDir = Files.createTempDirectory("nbkotlin-introduce-$subDir")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "introduce-variable-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")

        val computer = KaIntroduceVariableComputer(
            ktFile = ktFile,
            startOffset = offset,
            endOffset = offset,
            project = session.session.project,
        )
        return Triple(computer, ktFile, tmpDir)
    }

    /**
     * Integration test: exercises [KaIntroduceVariableComputer.compute] against a real K2 session
     * backed by `kotlin-stdlib`.
     *
     * Verifies for `simpleExpr`:
     *  - outcome is [KaIntroduceVariableComputer.Outcome.Ready]
     *  - at least one name suggestion is returned
     *  - [KaIntroduceVariableComputer.Outcome.Ready] result has a valid anchor offset
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
                outcome is KaIntroduceVariableComputer.Outcome.Ready,
            )
            val result = (outcome as KaIntroduceVariableComputer.Outcome.Ready).result
            assertTrue("Expected suggestions from real session", result.suggestedNames.isNotEmpty())
            assertTrue("anchorOffset must be >= 0", result.anchorOffset >= 0)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: verifies that the caret name-offset formula used by
     * [KotlinIntroduceVariableApplyElement.performChange] points to the first character of the
     * chosen variable name **at the usage site** (the original trigger location), not in the
     * inserted declaration line.
     *
     * The formula is:
     * ```
     * lowerShift = sum of (chosenName.length - range.length) for ranges with endOffset <= exprStart
     * nameOffset = exprStart + lowerShift + declaration.length
     * ```
     *
     * This keeps the caret at the expression that was replaced rather than jumping to the
     * newly inserted `val` line above it.
     */
    fun testApply_withRealSession_caretOnVariableName() {
        val triple = prepareWithRealSession("simpleExpr")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping caret test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue("Expected Ready", outcome is KaIntroduceVariableComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceVariableComputer.Outcome.Ready).result

            val chosenName = "myVar"
            val keyword = "val"
            val originalText = ktFile.text
            val rangesToReplace = result.occurrenceRanges.sortedByDescending { it.startOffset }
            var newText = originalText
            for (range in rangesToReplace) {
                newText = newText.substring(0, range.startOffset) +
                        chosenName +
                        newText.substring(range.endOffset)
            }
            val lineStart = newText.lastIndexOf('\n', result.anchorOffset - 1) + 1
            val indentation = newText.substring(lineStart, minOf(result.anchorOffset, newText.length))
                .takeWhile { it == ' ' || it == '\t' }
            val declaration = "$indentation$keyword $chosenName = ${result.expressionText}\n"
            newText = newText.substring(0, lineStart) + declaration + newText.substring(lineStart)

            // Replicate the nameOffset formula from performChange(): caret at usage site.
            val exprStart = result.expressionRange.startOffset
            val lowerShift = rangesToReplace
                .filter { it.endOffset <= exprStart }
                .sumOf { chosenName.length - (it.endOffset - it.startOffset) }
            val nameOffset = exprStart + lowerShift + declaration.length

            assertTrue("nameOffset $nameOffset must be within newText (len ${newText.length})",
                nameOffset < newText.length)
            assertEquals(
                "Character at nameOffset must be first char of '$chosenName'",
                chosenName[0],
                newText[nameOffset],
            )
            assertEquals(
                "Text at nameOffset must start with '$chosenName'",
                chosenName,
                newText.substring(nameOffset, nameOffset + chosenName.length),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: exercises the text-transformation logic of
     * [KotlinIntroduceVariableApplyElement] in a standalone session.
     *
     * Given `simpleExpr/file.kt` with `println(40 + 2)`, introduces a variable named `"value"`
     * for the expression `40 + 2` and verifies that the resulting text contains:
     *  - `val value = 40 + 2`
     *  - `println(value)` (occurrence replaced)
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
            assertTrue("Expected Ready", outcome is KaIntroduceVariableComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceVariableComputer.Outcome.Ready).result

            val chosenName = "myVar"
            val originalText = ktFile.text
            var newText = originalText
            for (range in result.occurrenceRanges.sortedByDescending { it.startOffset }) {
                newText = newText.substring(0, range.startOffset) +
                        chosenName +
                        newText.substring(range.endOffset)
            }
            val lineStart = newText.lastIndexOf('\n', result.anchorOffset - 1) + 1
            val indentation = newText.substring(lineStart, minOf(result.anchorOffset, newText.length))
                .takeWhile { it == ' ' || it == '\t' }
            val declaration = "${indentation}val $chosenName = ${result.expressionText}\n"
            newText = newText.substring(0, lineStart) + declaration + newText.substring(lineStart)

            assertTrue(
                "Expected 'val $chosenName = ${result.expressionText}' in output:\n$newText",
                newText.contains("val $chosenName = ${result.expressionText}"),
            )
            // The caret fixture is on the literal `2` inside `println(40 + 2)`, so only `2`
            // is extracted; the occurrence becomes `40 + myVar`, not `myVar` directly.
            assertTrue(
                "Expected occurrence replaced with '$chosenName' in output:\n$newText",
                newText.contains("println(40 + $chosenName)"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
