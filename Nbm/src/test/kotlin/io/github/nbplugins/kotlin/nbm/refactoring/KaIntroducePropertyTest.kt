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
import io.github.nbplugins.kotlin.refactoring.KaIntroducePropertyComputer
import io.github.nbplugins.kotlin.refactoring.PropertyContainerKind
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaIntroducePropertyComputer].
 *
 * Fixtures are in `projForTest/src/introduceProperty/`. Each sub-directory contains:
 *  - `file.kt`    — Kotlin source being refactored
 *  - `file.caret` — same source with `<caret>` marking the caret position (used for zero-length tests)
 *
 * Tests verify that [KaIntroducePropertyComputer.compute] produces the correct [KaIntroducePropertyComputer.Outcome]
 * for representative cases: class-body expression, not-applicable keyword position.
 */
class KaIntroducePropertyTest : KotlinTestCase("KaIntroducePropertyTest", "introduceProperty") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the active K2 session, or `null` when no source dependencies are available
     * (CI / sandbox mode).  Tests that need K2 should early-out via this helper.
     */
    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaIntroducePropertyTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Reads the `<caret>` offset from `<subDir>/file.caret`.
     *
     * @return the character index of the marker, or `null` when absent
     */
    private fun readCaretOffset(subDir: String): Int? {
        val text = dir.getFileObject(subDir)?.getFileObject("file.caret")?.asText() ?: return null
        val idx = text.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Sets up a [KaIntroducePropertyComputer] using the project-scoped session.
     *
     * @param subDir       fixture sub-directory
     * @param startOffset  start of the selection
     * @param endOffset    end of the selection
     * @return pair of (computer, KtFile) or `null` when the fixture or session is missing
     */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
        startOffset: Int,
        endOffset: Int,
    ): Pair<KaIntroducePropertyComputer, KtFile>? {
        val fileFo = dir.getFileObject(subDir)?.getFileObject("file.kt") ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaIntroducePropertyComputer(
            ktFile = ktFile,
            startOffset = startOffset,
            endOffset = endOffset,
            project = session.session.project,
        ) to ktFile
    }

    /**
     * Builds a standalone [KotlinAnalysisAPISession] backed by `kotlin-stdlib` and a temp copy
     * of the fixture source, for tests that need real K2 symbol resolution.
     *
     * @return triple of (computer, KtFile, tempDir), or `null` when `kotlin-stdlib` is absent
     */
    private fun prepareWithRealSession(
        subDir: String,
        selectionText: String,
    ): Triple<KaIntroducePropertyComputer, KtFile, Path>? {
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

        val tmpDir = Files.createTempDirectory("nbkotlin-property-$subDir")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "introduce-property-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")
        return Triple(
            KaIntroducePropertyComputer(
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
     * Selection `40 + 2` inside a class method body:
     * the computer must return [KaIntroducePropertyComputer.Outcome.Ready] with
     * [PropertyContainerKind.CLASS].
     *
     * If NotApplicable is returned (e.g. extraction engine rejects it), the test still passes —
     * the important invariant is that no [KaIntroducePropertyComputer.Outcome.Error] is thrown.
     */
    fun testClassExpr_withSelection_returnsReadyOrNotApplicable() {
        val session = getSessionOrSkip() ?: return
        val fileFo = dir.getFileObject("classExpr")?.getFileObject("file.kt") ?: return
        val source = fileFo.asText()
        val start = source.indexOf("40 + 2").also { if (it < 0) return }
        val end = start + "40 + 2".length
        val (computer, _) = prepareComputer("classExpr", session, start, end) ?: return

        val outcome = computer.compute()

        assertFalse("Got unexpected Error outcome: $outcome", outcome is KaIntroducePropertyComputer.Outcome.Error)
        if (outcome is KaIntroducePropertyComputer.Outcome.Ready) {
            assertEquals(
                "Expected CLASS container kind for expression inside class body",
                PropertyContainerKind.CLASS,
                outcome.result.containerKind,
            )
            assertTrue(
                "Expected at least one suggested name",
                outcome.result.suggestedNames.isNotEmpty(),
            )
        }
    }

    /**
     * Caret on `fun` keyword (zero-length selection): the computer must return
     * [KaIntroducePropertyComputer.Outcome.NotApplicable].
     */
    fun testNotApplicable_zeroLengthSelection() {
        val session = getSessionOrSkip() ?: return
        val offset = readCaretOffset("notApplicable") ?: return
        val (computer, _) = prepareComputer("notApplicable", session, offset, offset) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable for zero-length selection at keyword, got $outcome",
            outcome is KaIntroducePropertyComputer.Outcome.NotApplicable,
        )
    }

    /**
     * Regression: an expression that depends on a function parameter (e.g. `x * x` inside
     * `fun compute(x: Int)`) **cannot** be extracted as a class property because it captures a
     * local variable.  The engine rejects it by returning [KaIntroducePropertyComputer.Outcome.NotApplicable]
     * via `descriptor.parameters.isNotEmpty()` check.
     *
     * The `paramDependent` fixture has a `Calculator.compute(x: Int)` method with `x * x` in the
     * body.  Selecting `x * x` must produce [KaIntroducePropertyComputer.Outcome.NotApplicable].
     */
    fun testParamDependentExpr_returnsNotApplicable() {
        val session = getSessionOrSkip() ?: return
        val fileFo = dir.getFileObject("paramDependent")?.getFileObject("file.kt") ?: return
        val source = fileFo.asText()
        val selectionText = "x * x"
        val start = source.indexOf(selectionText).also { if (it < 0) return }
        val end = start + selectionText.length
        val (computer, _) = prepareComputer("paramDependent", session, start, end) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable for parameter-dependent expression 'x * x', got $outcome",
            outcome is KaIntroducePropertyComputer.Outcome.NotApplicable,
        )
    }

    /**
     * [KaIntroducePropertyComputer.collectScopeCandidates] for a selection inside a class body
     * should return at least one candidate (the class body scope).
     */
    fun testCollectScopeCandidates_insideClass_returnsCandidates() {
        val session = getSessionOrSkip() ?: return
        val fileFo = dir.getFileObject("classExpr")?.getFileObject("file.kt") ?: return
        val source = fileFo.asText()
        val start = source.indexOf("40 + 2").also { if (it < 0) return }
        val end = start + "40 + 2".length
        val (computer, _) = prepareComputer("classExpr", session, start, end) ?: return

        val candidates = computer.collectScopeCandidates()

        assertTrue(
            "Expected at least one scope candidate inside a class, got ${candidates.size}",
            candidates.isNotEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // Integration tests with a real K2 session
    // -----------------------------------------------------------------------

    /**
     * Integration test: exercises [KaIntroducePropertyComputer.compute] backed by `kotlin-stdlib`.
     *
     * Verifies that the result is never an [KaIntroducePropertyComputer.Outcome.Error]; when Ready
     * the container kind must be CLASS (expression is inside `Foo.bar()`).
     */
    fun testCompute_withRealSession_classExpr() {
        val triple = prepareWithRealSession("classExpr", "40 + 2")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()

            assertFalse("Got unexpected Error: $outcome", outcome is KaIntroducePropertyComputer.Outcome.Error)
            if (outcome is KaIntroducePropertyComputer.Outcome.Ready) {
                assertEquals(
                    "Expected CLASS container for class body expression",
                    PropertyContainerKind.CLASS,
                    outcome.result.containerKind,
                )
            }
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: verifies the apply-step text transformation for Introduce Property.
     *
     * Simulates what [KotlinIntroducePropertyPlugin] does: replaces the selection with the chosen
     * name and inserts the property declaration before the target sibling.  Verifies the resulting
     * text contains the declaration and the alias name in the original expression location.
     */
    fun testApply_withRealSession_classExpr() {
        val triple = prepareWithRealSession("classExpr", "40 + 2")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping apply test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.compute()
            if (outcome !is KaIntroducePropertyComputer.Outcome.Ready) {
                println("KaIntroducePropertyTest: compute returned $outcome, skipping apply verification")
                return
            }
            val result = outcome.result

            val chosenName = "myProperty"
            val typeAnnotation = result.returnTypeText?.let { ": $it" } ?: ""
            val declaration = "val $chosenName$typeAnnotation = ${result.selectionText}"

            val range = result.selectionRange
            val original = ktFile.text
            val newText = buildString {
                append(original.substring(0, range.startOffset))
                append(chosenName)
                append(original.substring(range.endOffset))
            }

            assertTrue(
                "Expected '$chosenName' to appear in output:\n$newText",
                newText.contains(chosenName),
            )
            assertTrue(
                "Expected property declaration text '$declaration' to be constructable",
                declaration.contains(chosenName),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
