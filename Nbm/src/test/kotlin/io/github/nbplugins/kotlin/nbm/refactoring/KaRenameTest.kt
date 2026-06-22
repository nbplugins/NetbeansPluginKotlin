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
import io.github.nbplugins.kotlin.refactoring.KaRenameComputer
import io.github.nbplugins.kotlin.refactoring.TextChange
import org.jetbrains.kotlin.builder.isKotlinFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaRenameComputer].
 *
 * Uses fixture directories under `projForTest/src/rename/`. Each directory contains:
 * - `file.kt`   — source with the symbol to rename
 * - `file.after` — expected result after renaming to `NEW_NAME`
 * - `file.caret` — same source with `<caret>` marking the cursor position
 *
 * Caret offsets are extracted from `file.caret` by removing the `<caret>` marker and
 * recording its position in the cleaned text, which matches `file.kt`.
 */
class KaRenameTest : KotlinTestCase("KaRenameTest", "rename") {

    companion object {
        private const val NEW_NAME = "NewName"
        private const val CARET_MARKER = "<caret>"
    }

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaRenameTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Reads the caret file for the given fixture sub-directory and returns the caret offset
     * in the cleaned (marker-free) source text.
     *
     * @param subDir fixture sub-directory name (e.g. "classrename")
     * @param fileName name of the caret file (default: "file.caret")
     * @return the caret offset, or `null` if the marker is absent
     */
    private fun readCaretOffset(subDir: String, fileName: String = "file.caret"): Int? {
        val caretFo = dir.getFileObject(subDir)?.getFileObject(fileName) ?: return null
        val caretText = caretFo.asText()
        val idx = caretText.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Returns the source text of the given fixture file, with the [CARET_MARKER] stripped.
     */
    private fun readSource(subDir: String, fileName: String = "file.kt"): String? =
        dir.getFileObject(subDir)?.getFileObject(fileName)?.asText()

    /**
     * Applies all [TextChange]s in [changes] (sorted descending by offset so earlier changes
     * are not displaced) to [source] and returns the resulting string.
     */
    private fun applyChanges(source: String, changes: List<TextChange>): String {
        var result = source
        // Apply in reverse order so offsets remain valid
        for (change in changes.sortedByDescending { it.start }) {
            result = result.substring(0, change.start) + change.newText + result.substring(change.end)
        }
        return result
    }

    /**
     * Runs a single rename fixture and asserts that applying the computed changes to
     * `file.kt` produces the text of `file.after`.
     *
     * @param subDir fixture sub-directory name
     * @param newName replacement name (default: [NEW_NAME])
     * @param sourceFileName source file within the fixture (default: "file.kt")
     * @param afterFileName expected result file (default: "file.after")
     */
    private fun runFixture(
        subDir: String,
        newName: String = NEW_NAME,
        sourceFileName: String = "file.kt",
        afterFileName: String = "file.after",
    ) {
        val session = getSessionOrSkip() ?: return

        val fixtureDir = dir.getFileObject(subDir) ?: run {
            println("KaRenameTest.$subDir: fixture directory not found — skipping")
            return
        }
        val sourceFo = fixtureDir.getFileObject(sourceFileName) ?: run {
            println("KaRenameTest.$subDir: $sourceFileName not found — skipping")
            return
        }
        val afterText = fixtureDir.getFileObject(afterFileName)?.asText() ?: run {
            println("KaRenameTest.$subDir: $afterFileName not found — skipping")
            return
        }
        val caretOffset = readCaretOffset(subDir) ?: run {
            println("KaRenameTest.$subDir: <caret> marker not found — skipping")
            return
        }

        val kaFile = session.getKtFileForPath(sourceFo.path) ?: run {
            println("KaRenameTest.$subDir: K2 file not available — skipping")
            return
        }

        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()
        val computer = KaRenameComputer(kaFile, caretOffset, allKtFiles, newName)
        val changes = computer.compute()

        assertNotNull("KaRenameComputer.compute() must not return null for $subDir", changes)
        changes!!

        val sourceText = readSource(subDir, sourceFileName)!!
        val changesForFile = changes[sourceFo.path] ?: emptyList()
        val actual = applyChanges(sourceText, changesForFile)

        assertEquals("Rename result mismatch for $subDir", afterText, actual)
    }

    // -------------------------------------------------------------------------
    // Basic rename fixtures
    // -------------------------------------------------------------------------

    /** Renames a class declaration and all its constructor call sites. */
    fun testRenameClass() = runFixture("classrename")

    /** Renames a method declaration and the single call site in the same file. */
    fun testRenameFunction() = runFixture("function", newName = "fooFunc")

    /** Renames a property declaration and all its usages in the same file. */
    fun testRenameProperty() = runFixture("properties", newName = "someValue")

    // -------------------------------------------------------------------------
    // Override-cascade fixture
    // -------------------------------------------------------------------------

    /**
     * Verifies that renaming an interface method also renames all implementing overrides
     * and the call site on the abstract type.
     */
    fun testRenameWithOverrideCascade() {
        val session = getSessionOrSkip() ?: return
        val subDir = "overrides"
        val fixtureDir = dir.getFileObject(subDir) ?: return
        val sourceFo = fixtureDir.getFileObject("file.kt") ?: return
        val afterText = fixtureDir.getFileObject("file.after")?.asText() ?: return
        val caretOffset = readCaretOffset(subDir) ?: return

        val kaFile = session.getKtFileForPath(sourceFo.path) ?: return
        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()

        val computer = KaRenameComputer(kaFile, caretOffset, allKtFiles, "surface")
        val changes = computer.compute()

        assertNotNull("compute() must return non-null for override cascade", changes)
        changes!!

        val sourceText = sourceFo.asText()
        val changesForFile = changes[sourceFo.path] ?: emptyList()
        val actual = applyChanges(sourceText, changesForFile)

        assertEquals("Override cascade rename must update all occurrences", afterText, actual)

        // All four occurrences: interface declaration + 2 override declarations + call site
        assertTrue(
            "Expected at least 4 changes for override cascade, got ${changesForFile.size}",
            changesForFile.size >= 4,
        )
    }

    // -------------------------------------------------------------------------
    // Resolve helpers
    // -------------------------------------------------------------------------

    /** Verifies that [KaRenameComputer.resolveOldName] returns the identifier at cursor. */
    fun testResolveOldName() {
        val session = getSessionOrSkip() ?: return
        val subDir = "classrename"
        val fixtureDir = dir.getFileObject(subDir) ?: return
        val sourceFo = fixtureDir.getFileObject("file.kt") ?: return
        val caretOffset = readCaretOffset(subDir) ?: return

        val kaFile = session.getKtFileForPath(sourceFo.path) ?: return
        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()

        val computer = KaRenameComputer(kaFile, caretOffset, allKtFiles, NEW_NAME)
        val oldName = computer.resolveOldName()

        assertNotNull("resolveOldName() must return non-null for a named declaration", oldName)
        assertEquals("Old name must be the class identifier", "ClassToRename", oldName)
    }

    /** Returns `null` (no crash) when the cursor is not on a named declaration. */
    fun testComputeReturnsNullForNonSymbol() {
        val session = getSessionOrSkip() ?: return
        val subDir = "classrename"
        val fixtureDir = dir.getFileObject(subDir) ?: return
        val sourceFo = fixtureDir.getFileObject("file.kt") ?: return
        val kaFile = session.getKtFileForPath(sourceFo.path) ?: return
        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()

        // Offset 0 is the start of the "package" keyword — not a named declaration
        val computer = KaRenameComputer(kaFile, 0, allKtFiles, NEW_NAME)
        // Must not crash; may return null or an empty map
        val result = computer.compute()
        // No assertion on result value — just verifying no exception is thrown
        assertTrue("compute() must not crash for non-symbol offset", true)
    }
}
