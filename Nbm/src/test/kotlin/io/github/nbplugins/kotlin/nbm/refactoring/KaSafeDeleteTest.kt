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
import utils.KotlinTestCase

/**
 * Unit tests for [KaSafeDeleteComputer].
 *
 * Fixtures are in `projForTest/src/safeDelete/`. Each sub-directory contains:
 * - `file.kt` — Kotlin source with the symbol under test
 * - `file.caret` — same source with `<caret>` marking the cursor position on the symbol
 * - optional `caller.kt` — additional file that references the symbol (for "used" cases)
 *
 * Tests verify the set of usages found by [KaSafeDeleteComputer.compute]:
 * - Unused symbols → empty usages map
 * - Used symbols → non-empty usages map
 */
class KaSafeDeleteTest : KotlinTestCase("KaSafeDeleteTest", "safeDelete") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaSafeDeleteTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    private fun readCaretOffset(subDir: String, fileName: String = "file.caret"): Int? {
        val caretFo = dir.getFileObject(subDir)?.getFileObject(fileName) ?: return null
        val caretText = caretFo.asText()
        val idx = caretText.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Resolves the `KtFile` for the given fixture file, loads it into the session,
     * and returns both the `KtFile` and the set of all fixture `FileObject`s in [subDir].
     */
    private fun resolveFixture(subDir: String, session: KotlinAnalysisAPISession):
            Triple<org.jetbrains.kotlin.psi.KtFile, Int, Set<org.openide.filesystems.FileObject>>? {
        val subFo = dir.getFileObject(subDir) ?: return null
        val fileFo = subFo.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null

        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null

        // All .kt files in the fixture sub-directory
        val allFiles = subFo.children
            .filter { it.hasExt("kt") }
            .toSet()

        return Triple(ktFile, offset, allFiles)
    }

    /**
     * An unused function has no references — [KaSafeDeleteComputer.compute] must return
     * a result with an empty [KaSafeDeleteResult.usages] map.
     */
    fun testUnused_noUsages() {
        val session = getSessionOrSkip() ?: return
        val (ktFile, offset, allFiles) = resolveFixture("unused", session) ?: return

        val result = KaSafeDeleteComputer(ktFile, offset, session, allFiles).compute()

        assertNotNull("compute() must return a result for an unused symbol", result)
        assertTrue(
            "Expected no usages for an unused function, got: ${result!!.usages}",
            result.usages.isEmpty()
        )
        assertEquals("unusedFunction", result.declarationName)
    }

    /**
     * A function that is called from another file has one usage — [KaSafeDeleteComputer.compute]
     * must return a non-empty [KaSafeDeleteResult.usages] map.
     */
    fun testUsed_reportsUsages() {
        val session = getSessionOrSkip() ?: return
        val (ktFile, offset, allFiles) = resolveFixture("used", session) ?: return

        val result = KaSafeDeleteComputer(ktFile, offset, session, allFiles).compute()

        assertNotNull("compute() must return a result for a used symbol", result)
        assertFalse(
            "Expected at least one usage for usedFunction(), but found none",
            result!!.usages.isEmpty()
        )
        val totalUsages = result.usages.values.sumOf { it.size }
        assertEquals("Expected exactly one call-site usage", 1, totalUsages)
    }
}
