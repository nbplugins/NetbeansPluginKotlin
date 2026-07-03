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
import io.github.nbplugins.kotlin.refactoring.GenerateOutcome
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

    /**
     * Selection spanning `println(a + b)` where both `a` and `b` are locals declared in the
     * enclosing scope: the computer must detect **both** as captured parameters, in declaration
     * order. This is the E9 Phase 0 diagnostic case (see docs/plans/e9-idea-port.md) — it
     * confirms whether the analysis half already reports multi-parameter captures correctly.
     */
    fun testMultiParam_detectsBothCapturedLocals() {
        val session = getSessionOrSkip() ?: return
        val computer = prepareComputer("multiParam", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready outcome for multi-local capture, got $outcome",
            outcome is KaExtractFunctionComputer.Outcome.Ready,
        )
        val result = (outcome as KaExtractFunctionComputer.Outcome.Ready).result
        assertEquals(
            "Expected parameters [a, b], got ${result.parameters.map { it.name }}",
            listOf("a", "b"),
            result.parameters.map { it.name },
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
     * Integration test: exercises [KaExtractFunctionComputer.compute] against a real K2 session
     * for the E9 Phase 0 diagnostic case — `println(a + b)` where `a` and `b` are locals declared
     * in the enclosing scope, using the **default (innermost)** destination scope.
     *
     * Extracting to the innermost enclosing block produces a *local* function nested right where
     * the selection was, which in Kotlin can access `a`/`b` directly as closures — so no
     * parameters are expected here. See [testCompute_withRealSession_multiParam_topLevelScope]
     * for the case that does require `a`/`b` as parameters.
     */
    fun testCompute_withRealSession_multiParam() {
        val triple = prepareWithRealSession("multiParam")
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
            assertEquals(
                "Expected no parameters for a local function (closures over a/b), got ${result.parameters.map { it.name }}",
                emptyList<String>(),
                result.parameters.map { it.name },
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Same fixture as [testCompute_withRealSession_multiParam], but forces extraction to the
     * **top-level** scope (outside `outer()`) via `targetSiblingOffset = 0`. Outside the
     * enclosing function, `a`/`b` are no longer visible as closures, so they must become explicit
     * parameters — this is the `outer()` example from docs/plans/e9-idea-port.md.
     */
    fun testCompute_withRealSession_multiParam_topLevelScope() {
        val triple = prepareWithRealSession("multiParam")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute(targetSiblingOffset = 0)

            assertTrue(
                "Expected Ready from real session, got $outcome",
                outcome is KaExtractFunctionComputer.Outcome.Ready,
            )
            val result = (outcome as KaExtractFunctionComputer.Outcome.Ready).result
            assertEquals(
                "Expected parameters [a, b] when extracting to top-level scope, got ${result.parameters.map { it.name }}",
                listOf("a", "b"),
                result.parameters.map { it.name },
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (E9 Phase 2): exercises [KaExtractFunctionComputer.generate] — the real
     * IDEA K2 generation engine (`Generator.generateDeclaration`) — against the `outer()` example
     * from `docs/plans/e9-idea-port.md`, extracting to the top-level scope so `a`/`b` must become
     * parameters. Verifies the mutated file text contains the expected call site and function
     * signature, i.e. that the ported generator (not hand-rolled string templates) produces
     * IDEA-equivalent output.
     */
    fun testGenerate_withRealSession_multiParam_topLevelScope() {
        val triple = prepareWithRealSession("multiParam")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val originalText = ktFile.text
            val outcome = computer.generate(chosenName = "extracted", targetSiblingOffset = 0)

            assertTrue(
                "Expected Ready from real session, got $outcome",
                outcome is GenerateOutcome.Ready,
            )
            val edit = (outcome as GenerateOutcome.Ready).edit

            // Reconstruct the post-edit text the way the NetBeans Document apply step would.
            val newText = originalText.substring(0, edit.changedRange.startOffset) +
                edit.replacementText +
                originalText.substring(edit.changedRange.endOffset)

            assertEquals("Reconstructed edit should exactly match the mutated KtFile text", ktFile.text, newText)
            assertTrue(
                "Expected call site 'extracted(a, b)' in generated text, got:\n$newText",
                newText.contains("extracted(a, b)"),
            )
            assertTrue(
                "Expected signature 'fun extracted(a: Int, b: Int)' in generated text, got:\n$newText",
                newText.contains("fun extracted(a: Int, b: Int)"),
            )
            assertTrue(
                "Caret offset ${edit.caretOffset} should point at the call site within the new text",
                newText.startsWith("extracted", edit.caretOffset),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (E9 Phase 2/3): exercises [KaExtractFunctionComputer.generate] — the real
     * IDEA K2 generation engine, not hand-rolled text templates — against `simpleExpr/file.kt`
     * (`println(40 + 2)`), extracting `40 + 2` into a function named `"extracted"`.
     *
     * Verifies that the mutated text:
     *  - contains `fun extracted(): Int` at the top level
     *  - contains `println(extracted())` replacing the original expression
     */
    fun testGenerate_withRealSession_simpleExpr() {
        val triple = prepareWithRealSession("simpleExpr")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping apply integration test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.generate(chosenName = "extracted")

            assertTrue("Expected Ready, got $outcome", outcome is GenerateOutcome.Ready)
            val newText = ktFile.text

            assertTrue(
                "Expected 'fun extracted(): Int' in output:\n$newText",
                newText.contains("fun extracted(): Int"),
            )
            assertTrue(
                "Expected 'println(extracted())' call in output:\n$newText",
                newText.contains("println(extracted())"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (E9 Phase 4): a return-value case — `val r = a + b` where the selection
     * is `a + b`, extracted to **top-level** scope (so `a`/`b` must be parameters) via
     * `returnValue/file.kt`. Verifies the real generator produces a non-Unit return: the
     * signature includes `: Int` and the body returns the expression.
     */
    fun testGenerate_withRealSession_returnValue_topLevelScope() {
        val triple = prepareWithRealSession("returnValue")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.generate(chosenName = "extracted", targetSiblingOffset = 0)

            assertTrue("Expected Ready, got $outcome", outcome is GenerateOutcome.Ready)
            val newText = ktFile.text

            assertTrue(
                "Expected 'fun extracted(a: Int, b: Int): Int' in output:\n$newText",
                newText.contains("fun extracted(a: Int, b: Int): Int"),
            )
            assertTrue(
                "Expected 'val r = extracted(a, b)' call site in output:\n$newText",
                newText.contains("val r = extracted(a, b)"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (E9 Phase 4): a multi-statement selection (`println(a)` + `println(b)`)
     * via `multiStatement/file.kt`, extracted to the default (innermost) scope. Verifies both
     * statements land in the extracted body and a single call replaces the whole selection.
     */
    fun testGenerate_withRealSession_multiStatement() {
        val triple = prepareWithRealSession("multiStatement")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.generate(chosenName = "extracted")

            assertTrue("Expected Ready, got $outcome", outcome is GenerateOutcome.Ready)
            val newText = ktFile.text

            assertTrue(
                "Expected both statements in the extracted body:\n$newText",
                newText.contains("println(a)") && newText.contains("println(b)"),
            )
            assertTrue(
                "Expected a single 'extracted()' call replacing the selection:\n$newText",
                newText.contains("extracted()"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (E9 Phase 4): extraction to the **class member** scope via
     * `classMember/file.kt` — `Calculator.compute()` selects `println(a + b)` and extracts to a
     * sibling member of `Calculator`, not a local function. Verifies the new function is inserted
     * inside the class body (not at top level) and the call site is updated.
     */
    fun testGenerate_withRealSession_classMemberScope() {
        val triple = prepareWithRealSession("classMember")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val classBodyScope = computer.collectScopeCandidates()
                .firstOrNull { it.label.contains("Calculator") }
                ?: error("Expected a 'class Calculator' scope candidate")

            val outcome = computer.generate(chosenName = "extracted", targetSiblingOffset = classBodyScope.targetSiblingOffset)

            assertTrue("Expected Ready, got $outcome", outcome is GenerateOutcome.Ready)
            val newText = ktFile.text

            assertTrue(
                "Expected 'extracted(a: Int, b: Int)' as a member of Calculator, got:\n$newText",
                newText.contains("class Calculator {") &&
                    newText.substringAfter("class Calculator {").contains("fun extracted(a: Int, b: Int)"),
            )
            assertTrue(
                "Expected 'extracted(a, b)' call site in output:\n$newText",
                newText.contains("extracted(a, b)"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that Extract Function generation succeeds for class-member scope, and that
     * [KaExtractFunctionEdit.formatEndOffset] covers the entire inserted declaration including its
     * closing brace.
     *
     * Regression test: a naive diff between old/new file text (longest-common-prefix/suffix trim)
     * can under-count the changed region when the tail of the inserted declaration coincidentally
     * matches the tail of the original text (e.g. both end in `"}\n}\n"` — the new function's
     * closing brace plus the class's closing brace vs. the old `compute()`'s closing brace plus
     * the class's closing brace). That caused the NetBeans plugin's reformat range to stop short of
     * the new function's own closing brace, leaving it unindented after Extract Function. Fixed by
     * widening the formatted range to `formatEndOffset`, which is always at least as large as the
     * inserted declaration's real PSI end offset.
     */
    fun testGenerate_withRealSession_classMemberScope_isProperlyFormatted() {
        val triple = prepareWithRealSession("classMember")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val classBodyScope = computer.collectScopeCandidates()
                .firstOrNull { it.label.contains("Calculator") }
                ?: error("Expected a 'class Calculator' scope candidate")

            val outcome = computer.generate(chosenName = "extracted", targetSiblingOffset = classBodyScope.targetSiblingOffset)

            assertTrue("Expected Ready, got $outcome", outcome is GenerateOutcome.Ready)
            val edit = (outcome as GenerateOutcome.Ready).edit
            val actual = ktFile.text

            // Basic smoke test: the function exists in the output (formatting/positioning verified separately)
            assertTrue(
                "Expected extracted function in output, got:\n$actual",
                actual.contains("fun extracted(") && actual.contains("extracted(a, b)")
            )

            // The formatted region must extend at least to the end of the extracted function's
            // own closing brace, not stop short of it (regression: diff under-counted this range).
            val declarationEnd = actual.indexOf("fun extracted(").let { start ->
                var depth = 0
                var i = actual.indexOf('{', start)
                depth = 1
                i++
                while (i < actual.length && depth > 0) {
                    when (actual[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    i++
                }
                i
            }
            assertTrue(
                "Expected formatEndOffset (${edit.formatEndOffset}) to cover the extracted function's closing brace (ends at $declarationEnd), got:\n$actual",
                edit.formatEndOffset >= declarationEnd,
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
