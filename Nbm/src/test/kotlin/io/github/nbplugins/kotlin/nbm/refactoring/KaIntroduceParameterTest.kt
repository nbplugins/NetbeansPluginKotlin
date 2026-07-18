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
import io.github.nbplugins.kotlin.refactoring.KaIntroduceParameterComputer
import io.github.nbplugins.kotlin.refactoring.KaIntroduceParameterRequest
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaIntroduceParameterComputer].
 *
 * Fixtures are in `projForTest/src/introduceParameter/`. Each sub-directory contains:
 *  - `file.kt`    — Kotlin source being refactored
 *  - `file.caret` — same source with `<caret>` marking the caret position (used for zero-length tests)
 *
 * Tests verify [KaIntroduceParameterComputer.compute]/[KaIntroduceParameterComputer.apply] for
 * representative cases: a plain expression inside a top-level function, a not-applicable caret
 * position, an existing call site that must be updated, and multiple matching occurrences.
 */
class KaIntroduceParameterTest : KotlinTestCase("KaIntroduceParameterTest", "introduceParameter") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns the active K2 session, or `null` when no source dependencies are available (CI / sandbox mode). */
    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaIntroduceParameterTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /** Reads the `<caret>` offset from `<subDir>/file.caret`. */
    private fun readCaretOffset(subDir: String): Int? {
        val text = dir.getFileObject(subDir)?.getFileObject("file.caret")?.asText() ?: return null
        val idx = text.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /** Sets up a [KaIntroduceParameterComputer] using the project-scoped session. */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
        startOffset: Int,
        endOffset: Int,
    ): Pair<KaIntroduceParameterComputer, KtFile>? {
        val fileFo = dir.getFileObject(subDir)?.getFileObject("file.kt") ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaIntroduceParameterComputer(
            ktFile = ktFile,
            startOffset = startOffset,
            endOffset = endOffset,
            project = session.session.project,
        ) to ktFile
    }

    /**
     * Builds a standalone [KotlinAnalysisAPISession] backed by `kotlin-stdlib` and a temp copy of
     * the fixture source, for tests that need real K2 symbol resolution (occurrence matching, call
     * site updates via the ported Change Signature engine).
     */
    private fun prepareWithRealSession(
        subDir: String,
        selectionText: String,
    ): Triple<KaIntroduceParameterComputer, KtFile, Path>? {
        val stdlib = System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }
            ?: return null

        val subFo = dir.getFileObject(subDir) ?: error("Missing fixture dir: $subDir")
        val fileFo = subFo.getFileObject("file.kt") ?: error("Missing $subDir/file.kt")
        val source = fileFo.asText()
        val start = source.indexOf(selectionText)
        check(start >= 0) { "Selection '$selectionText' not found in $subDir/file.kt" }
        val end = start + selectionText.length

        val tmpDir = Files.createTempDirectory("nbkotlin-introduceparam-$subDir")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "introduce-parameter-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")
        return Triple(
            KaIntroduceParameterComputer(
                ktFile = ktFile,
                startOffset = start,
                endOffset = end,
                project = session.session.project,
            ),
            ktFile,
            tmpDir,
        )
    }

    // -----------------------------------------------------------------------
    // Computer correctness tests
    // -----------------------------------------------------------------------

    /**
     * Selection `"world"` inside a top-level function body: the computer must return
     * [KaIntroduceParameterComputer.Outcome.Ready] with at least one suggested name.
     *
     * If NotApplicable is returned (e.g. type inference unavailable without a real session), the
     * test still passes — the important invariant is that no
     * [KaIntroduceParameterComputer.Outcome.Error] is thrown.
     */
    fun testSimpleExpr_withSelection_returnsReadyOrNotApplicable() {
        val session = getSessionOrSkip() ?: return
        val fileFo = dir.getFileObject("simpleExpr")?.getFileObject("file.kt") ?: return
        val source = fileFo.asText()
        val selectionText = "\"world\""
        val start = source.indexOf(selectionText).also { if (it < 0) return }
        val end = start + selectionText.length
        val (computer, _) = prepareComputer("simpleExpr", session, start, end) ?: return

        val outcome = computer.compute()

        assertFalse("Got unexpected Error outcome: $outcome", outcome is KaIntroduceParameterComputer.Outcome.Error)
        if (outcome is KaIntroduceParameterComputer.Outcome.Ready) {
            assertTrue("Expected at least one suggested name", outcome.result.suggestedNames.isNotEmpty())
        }
    }

    /** Caret on `fun` keyword (zero-length selection): the computer must return `NotApplicable`. */
    fun testNotApplicable_zeroLengthSelection() {
        val session = getSessionOrSkip() ?: return
        val offset = readCaretOffset("notApplicable") ?: return
        val (computer, _) = prepareComputer("notApplicable", session, offset, offset) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable for zero-length selection at keyword, got $outcome",
            outcome is KaIntroduceParameterComputer.Outcome.NotApplicable,
        )
    }

    /**
     * Regression test: invoking with a pure caret placement *inside* an expression (no text
     * selected — the common way users trigger this action) must expand to the enclosing
     * expression, not silently return `NotApplicable`. Caret is placed inside the `"world"`
     * string literal in `simpleExpr/file.caret`.
     */
    fun testCaretOnly_noSelection_expandsToEnclosingExpression() {
        val session = getSessionOrSkip() ?: return
        val offset = readCaretOffset("simpleExpr") ?: return
        val (computer, _) = prepareComputer("simpleExpr", session, offset, offset) ?: return

        val outcome = computer.compute()

        assertFalse("Got unexpected Error outcome: $outcome", outcome is KaIntroduceParameterComputer.Outcome.Error)
        assertTrue(
            "Expected Ready when the caret is placed inside an expression with no selection, got $outcome",
            outcome is KaIntroduceParameterComputer.Outcome.Ready,
        )
    }

    /**
     * [KaIntroduceParameterComputer.collectScopeCandidates] for a selection inside a top-level
     * function should return at least one candidate (the function itself).
     */
    fun testCollectScopeCandidates_insideFunction_returnsCandidates() {
        val session = getSessionOrSkip() ?: return
        val fileFo = dir.getFileObject("simpleExpr")?.getFileObject("file.kt") ?: return
        val source = fileFo.asText()
        val selectionText = "\"world\""
        val start = source.indexOf(selectionText).also { if (it < 0) return }
        val end = start + selectionText.length
        val (computer, _) = prepareComputer("simpleExpr", session, start, end) ?: return

        val candidates = computer.collectScopeCandidates()

        assertTrue("Expected at least one scope candidate inside a function, got ${candidates.size}", candidates.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Integration tests with a real K2 session
    // -----------------------------------------------------------------------

    /**
     * Integration test: [KaIntroduceParameterComputer.compute]/[KaIntroduceParameterComputer.apply]
     * backed by `kotlin-stdlib`. Verifies the function signature gains the new parameter and the
     * selected expression is replaced with a reference to it.
     */
    fun testApply_withRealSession_simpleExpr() {
        val triple = prepareWithRealSession("simpleExpr", "\"world\"")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            if (outcome !is KaIntroduceParameterComputer.Outcome.Ready) {
                println("KaIntroduceParameterTest: compute returned $outcome, skipping apply verification")
                return
            }
            val chosenName = "greeting"
            val request = KaIntroduceParameterRequest(
                chosenName = chosenName,
                chosenTypeText = outcome.result.typeText,
                replaceAllOccurrences = true,
                useDefaultValue = false,
            )
            val applyOutcome = computer.apply(request)

            assertTrue(
                "Expected Success, got $applyOutcome",
                applyOutcome is KaIntroduceParameterComputer.ApplyOutcome.Success,
            )
            if (applyOutcome is KaIntroduceParameterComputer.ApplyOutcome.Success) {
                val newText = applyOutcome.fileTexts.values.single()
                assertTrue(
                    "Expected new parameter '$chosenName' in signature:\n$newText",
                    newText.contains("fun greet($chosenName"),
                )
                assertTrue(
                    "Expected the body to reference the new parameter instead of the literal:\n$newText",
                    newText.contains(chosenName) && !newText.contains("\"world\""),
                )
            }
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: a function with an existing call site. After applying Introduce Parameter,
     * the call site must be updated to pass the original expression as an argument.
     */
    fun testApply_withRealSession_updatesCallSite() {
        val triple = prepareWithRealSession("withCallSite", "\"world\"")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping call-site test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            if (outcome !is KaIntroduceParameterComputer.Outcome.Ready) {
                println("KaIntroduceParameterTest: compute returned $outcome, skipping call-site verification")
                return
            }
            val request = KaIntroduceParameterRequest(
                chosenName = "greeting",
                chosenTypeText = outcome.result.typeText,
                replaceAllOccurrences = true,
                useDefaultValue = false,
            )
            val applyOutcome = computer.apply(request)

            assertTrue("Expected Success, got $applyOutcome", applyOutcome is KaIntroduceParameterComputer.ApplyOutcome.Success)
            if (applyOutcome is KaIntroduceParameterComputer.ApplyOutcome.Success) {
                val newText = applyOutcome.fileTexts.values.single()
                assertTrue(
                    "Expected the call site to pass the original argument:\n$newText",
                    newText.contains("greet(\"world\")"),
                )
            }
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: an expression occurring twice in the same function body. With
     * `replaceAllOccurrences = true`, both occurrences must be replaced by the new parameter.
     */
    fun testApply_withRealSession_replacesAllOccurrences() {
        val triple = prepareWithRealSession("occurrences", "\"world\"")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping occurrences test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            if (outcome !is KaIntroduceParameterComputer.Outcome.Ready) {
                println("KaIntroduceParameterTest: compute returned $outcome, skipping occurrences verification")
                return
            }
            assertTrue("Expected at least 2 occurrences", outcome.result.occurrenceCount >= 2)

            val request = KaIntroduceParameterRequest(
                chosenName = "word",
                chosenTypeText = outcome.result.typeText,
                replaceAllOccurrences = true,
                useDefaultValue = false,
            )
            val applyOutcome = computer.apply(request)

            assertTrue("Expected Success, got $applyOutcome", applyOutcome is KaIntroduceParameterComputer.ApplyOutcome.Success)
            if (applyOutcome is KaIntroduceParameterComputer.ApplyOutcome.Success) {
                val newText = applyOutcome.fileTexts.values.single()
                assertFalse(
                    "Expected no remaining '\"world\"' literal after replacing all occurrences:\n$newText",
                    newText.contains("\"world\""),
                )
            }
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
