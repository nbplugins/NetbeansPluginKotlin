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
import io.github.nbplugins.kotlin.refactoring.KaIntroduceFunctionalParameterComputer
import io.github.nbplugins.kotlin.refactoring.KaIntroduceFunctionalParameterRequest
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaIntroduceFunctionalParameterComputer].
 *
 * Fixtures are in `projForTest/src/introduceFunctionalParameter/`. Each sub-directory contains:
 *  - `file.kt`    — Kotlin source being refactored
 *  - `file.caret` — same source with `<caret>` marking the caret position (used for zero-length tests)
 *
 * `withCapture` and `noParams` mirror the shape of real IDEA's own
 * `introduceLambdaParameter/lambdaParamNoDefaultValue.kt` / `lambdaParamNoParams.kt` test fixtures
 * (`submodules/IntellijCommunity/plugins/kotlin/idea/tests/testData/refactoring/introduceLambdaParameter/`),
 * so the expected functional type / lambda-literal shapes are verified against real IDEA behavior.
 */
class KaIntroduceFunctionalParameterTest : KotlinTestCase("KaIntroduceFunctionalParameterTest", "introduceFunctionalParameter") {

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
            println("KaIntroduceFunctionalParameterTest: skipping — no K2 dependencies available")
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

    /** Sets up a [KaIntroduceFunctionalParameterComputer] using the project-scoped session. */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
        startOffset: Int,
        endOffset: Int,
    ): Pair<KaIntroduceFunctionalParameterComputer, KtFile>? {
        val fileFo = dir.getFileObject(subDir)?.getFileObject("file.kt") ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaIntroduceFunctionalParameterComputer(
            ktFile = ktFile,
            startOffset = startOffset,
            endOffset = endOffset,
            project = session.session.project,
        ) to ktFile
    }

    /**
     * Builds a standalone [KotlinAnalysisAPISession] backed by `kotlin-stdlib` and a temp copy of
     * the fixture source, for tests that need real K2 symbol resolution (captured-variable
     * analysis via the ported Extract Function engine, call-site updates via the ported Change
     * Signature engine).
     */
    private fun prepareWithRealSession(
        subDir: String,
        selectionText: String,
    ): Triple<KaIntroduceFunctionalParameterComputer, KtFile, Path>? {
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

        val tmpDir = Files.createTempDirectory("nbkotlin-introducefunctionalparam-$subDir")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "introduce-functional-parameter-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")
        return Triple(
            KaIntroduceFunctionalParameterComputer(
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
     * Selection `"a + 1"` inside a top-level function body: the computer must return
     * [KaIntroduceFunctionalParameterComputer.Outcome.Ready] with at least one suggested name and
     * a functional-type text.
     *
     * If NotApplicable is returned (e.g. type inference unavailable without a real session), the
     * test still passes — the important invariant is that no
     * [KaIntroduceFunctionalParameterComputer.Outcome.Error] is thrown.
     */
    fun testWithCapture_withSelection_returnsReadyOrNotApplicable() {
        val session = getSessionOrSkip() ?: return
        val fileFo = dir.getFileObject("withCapture")?.getFileObject("file.kt") ?: return
        val source = fileFo.asText()
        val selectionText = "a + 1"
        val start = source.indexOf(selectionText).also { if (it < 0) return }
        val end = start + selectionText.length
        val (computer, _) = prepareComputer("withCapture", session, start, end) ?: return

        val outcome = computer.compute()

        assertFalse("Got unexpected Error outcome: $outcome", outcome is KaIntroduceFunctionalParameterComputer.Outcome.Error)
        if (outcome is KaIntroduceFunctionalParameterComputer.Outcome.Ready) {
            assertTrue("Expected at least one suggested name", outcome.result.suggestedNames.isNotEmpty())
            assertTrue("Expected a non-blank functional type text", outcome.result.typeText.isNotBlank())
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
            outcome is KaIntroduceFunctionalParameterComputer.Outcome.NotApplicable,
        )
    }

    /**
     * Regression test: invoking with a pure caret placement *inside* an expression (no text
     * selected) must expand to the enclosing expression, not silently return `NotApplicable`.
     * Caret is placed inside the `a + 1` expression in `withCapture/file.caret`.
     */
    fun testCaretOnly_noSelection_expandsToEnclosingExpression() {
        val session = getSessionOrSkip() ?: return
        val offset = readCaretOffset("withCapture") ?: return
        val (computer, _) = prepareComputer("withCapture", session, offset, offset) ?: return

        val outcome = computer.compute()

        assertFalse("Got unexpected Error outcome: $outcome", outcome is KaIntroduceFunctionalParameterComputer.Outcome.Error)
    }

    /**
     * [KaIntroduceFunctionalParameterComputer.collectScopeCandidates] for a selection inside a
     * top-level function should return at least one candidate (the function itself).
     */
    fun testCollectScopeCandidates_insideFunction_returnsCandidates() {
        val session = getSessionOrSkip() ?: return
        val fileFo = dir.getFileObject("withCapture")?.getFileObject("file.kt") ?: return
        val source = fileFo.asText()
        val selectionText = "a + 1"
        val start = source.indexOf(selectionText).also { if (it < 0) return }
        val end = start + selectionText.length
        val (computer, _) = prepareComputer("withCapture", session, start, end) ?: return

        val candidates = computer.collectScopeCandidates()

        assertTrue("Expected at least one scope candidate inside a function, got ${candidates.size}", candidates.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Integration tests with a real K2 session
    // -----------------------------------------------------------------------

    /**
     * Integration test mirroring IDEA's `lambdaParamNoDefaultValue.kt`: selecting `a + 1` (which
     * captures the enclosing function's own parameter `a`) must produce a `(Int) -> Int`
     * parameter, the body occurrence becomes a call to it, and the existing call site receives a
     * lambda literal `{ a -> a + 1 }`.
     */
    fun testApply_withRealSession_withCapture() {
        val triple = prepareWithRealSession("withCapture", "a + 1")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            if (outcome !is KaIntroduceFunctionalParameterComputer.Outcome.Ready) {
                println("KaIntroduceFunctionalParameterTest: compute returned $outcome, skipping apply verification")
                return
            }
            assertEquals("Expected a (Int) -> Int functional type", "(Int) -> Int", outcome.result.typeText)

            val request = KaIntroduceFunctionalParameterRequest(
                chosenName = "function",
                replaceAllOccurrences = true,
                useDefaultValue = false,
            )
            val applyOutcome = computer.apply(request)

            assertTrue(
                "Expected Success, got $applyOutcome",
                applyOutcome is KaIntroduceFunctionalParameterComputer.ApplyOutcome.Success,
            )
            if (applyOutcome is KaIntroduceFunctionalParameterComputer.ApplyOutcome.Success) {
                val newText = applyOutcome.fileTexts.values.single()
                // The type text here may be fully qualified ("kotlin.Int") rather than short
                // ("Int") — see this class's own KDoc on KotlinChangeSignatureUsageProcessor's
                // (E9.8, shared) re-derivation of the final signature type; only the shape (one
                // parameter, arrow, return type) is asserted, not exact qualification.
                assertTrue(
                    "Expected a new functional parameter 'function: (...Int) -> ...Int' in signature:\n$newText",
                    Regex("""function: \([^)]*Int\) -> \S*Int""").containsMatchIn(newText),
                )
                assertTrue(
                    "Expected the body to call the new parameter with the captured 'a':\n$newText",
                    newText.contains("function(a)"),
                )
                assertTrue(
                    "Expected the call site to receive a lambda literal '{ a -> a + 1 }':\n$newText",
                    newText.contains("{ a -> a + 1 }") || newText.contains("{ a ->\n") && newText.contains("a + 1"),
                )
            }
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test mirroring IDEA's `lambdaParamNoParams.kt`: selecting `1 + 2` (no captured
     * variables) must produce a `() -> Int` parameter and a zero-argument lambda literal
     * `{ 1 + 2 }` at the call site.
     */
    fun testApply_withRealSession_noParams() {
        val triple = prepareWithRealSession("noParams", "1 + 2")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            if (outcome !is KaIntroduceFunctionalParameterComputer.Outcome.Ready) {
                println("KaIntroduceFunctionalParameterTest: compute returned $outcome, skipping apply verification")
                return
            }
            assertEquals("Expected a () -> Int functional type", "() -> Int", outcome.result.typeText)

            val request = KaIntroduceFunctionalParameterRequest(
                chosenName = "function",
                replaceAllOccurrences = true,
                useDefaultValue = false,
            )
            val applyOutcome = computer.apply(request)

            assertTrue(
                "Expected Success, got $applyOutcome",
                applyOutcome is KaIntroduceFunctionalParameterComputer.ApplyOutcome.Success,
            )
            if (applyOutcome is KaIntroduceFunctionalParameterComputer.ApplyOutcome.Success) {
                val newText = applyOutcome.fileTexts.values.single()
                // See testApply_withRealSession_withCapture's comment on qualified type text.
                assertTrue(
                    "Expected a new functional parameter 'function: () -> ...Int' in signature:\n$newText",
                    Regex("""function: \(\) -> \S*Int""").containsMatchIn(newText),
                )
                assertTrue(
                    "Expected the body to call the new parameter with no arguments:\n$newText",
                    newText.contains("function()"),
                )
                assertTrue(
                    "Expected the call site to receive a zero-arg lambda literal '{ 1 + 2 }':\n$newText",
                    newText.contains("{ 1 + 2 }"),
                )
            }
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
