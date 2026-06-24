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
import io.github.nbplugins.kotlin.refactoring.KaInlineVariableComputer
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaInlineVariableComputer].
 *
 * Fixtures are in `projForTest/src/inlineVariable/`. Each sub-directory contains:
 *  - `file.kt` — Kotlin source with the property under test
 *  - `file.caret` — same source with `<caret>` marking the cursor position
 *
 * Tests verify that the IDEA-engine pathway (`extractInitialization` +
 * `createReplacementStrategyForProperty` + reference walk) produces the right [Outcome] for
 * representative cases. Application of the strategy (`replaceUsages` + `delete`) is exercised
 * end-to-end only in manual testing because it mutates the K2 session PSI and would leave
 * cross-test state if executed here.
 */
class KaInlineVariableTest : KotlinTestCase("KaInlineVariableTest", "inlineVariable") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    /**
     * Returns the active K2 session, or `null` when the standalone analysis API has no source
     * dependencies (CI / sandbox mode without K2 binaries). Tests that need K2 should early-out
     * via this helper to remain green in such environments.
     */
    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaInlineVariableTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Reads the caret offset from `<subDir>/file.caret` (the index of the `<caret>` marker).
     * Returns `null` when the file or marker is missing — tests should treat that as a setup error.
     */
    private fun readCaretOffset(subDir: String): Int? {
        val caretFo = dir.getFileObject(subDir)?.getFileObject("file.caret") ?: return null
        val caretText = caretFo.asText()
        val idx = caretText.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Locates `<subDir>/file.kt`, registers it via the session, and returns the prepared
     * computer plus the resolved [KtFile] for assertions.
     */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): Pair<KaInlineVariableComputer, KtFile>? {
        val subFo = dir.getFileObject(subDir) ?: return null
        val fileFo = subFo.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null

        val projectKtFiles = session.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()

        return KaInlineVariableComputer(
            cursorKtFile = ktFile,
            offset = offset,
            project = session.session.project,
            projectKtFiles = projectKtFiles,
        ) to ktFile
    }

    /**
     * Cursor on `val x = 42` with one read in the same function: the computer must return
     * [KaInlineVariableComputer.Outcome.Ready] with `declarationName == "x"` and exactly one
     * collected `KtReferenceExpression`.
     */
    fun testSimpleVal_oneUsage() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("simpleVal", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready, got $outcome",
            outcome is KaInlineVariableComputer.Outcome.Ready,
        )
        val result = (outcome as KaInlineVariableComputer.Outcome.Ready).result
        assertEquals("x", result.declarationName)
        val totalUsages = result.usages.values.sumOf { it.size }
        assertEquals("Expected exactly one usage of simpleVal.x", 1, totalUsages)
    }

    /**
     * Cursor on `val n = 10` with three reads (`println(n)`, `println(n + 1)`, `println(n * n)`):
     * `println(n * n)` references `n` twice, so the total collected references must be 4.
     */
    fun testMultipleUsages_collectsAllRefs() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("multipleUsages", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready, got $outcome",
            outcome is KaInlineVariableComputer.Outcome.Ready,
        )
        val result = (outcome as KaInlineVariableComputer.Outcome.Ready).result
        assertEquals("n", result.declarationName)
        val totalUsages = result.usages.values.sumOf { it.size }
        assertEquals(
            "Expected 4 references (println(n), println(n+1), println(n*n) twice), got $totalUsages",
            4,
            totalUsages,
        )
    }

    /**
     * Cursor on an unused `val`: the computer must still return [Outcome.Ready] (the property has
     * an initializer and no getter/setter), but the usages map must be empty.
     */
    fun testUnusedVal_readyWithNoUsages() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("unusedVal", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready for an unused val, got $outcome",
            outcome is KaInlineVariableComputer.Outcome.Ready,
        )
        val result = (outcome as KaInlineVariableComputer.Outcome.Ready).result
        assertEquals("unused", result.declarationName)
        assertTrue(
            "Expected no usages for unused val, got: ${result.usages}",
            result.usages.values.all { it.isEmpty() },
        )
    }
}
