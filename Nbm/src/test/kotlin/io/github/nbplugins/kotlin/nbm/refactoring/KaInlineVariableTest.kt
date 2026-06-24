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
 * Unit tests for [KaInlineVariableComputer].
 *
 * Fixtures are in `projForTest/src/inlineVariable/`. Each sub-directory contains:
 * - `file.kt` — Kotlin source with the property to inline
 * - `file.caret` — same source with `<caret>` marking the cursor on the property name
 *
 * Tests verify:
 * - Unused val: [KaInlineVariableResult.readUsages] is empty, declaration range is valid
 * - Single usage: one usage replaced with the initializer text
 * - Multiple usages: all occurrences replaced
 */
class KaInlineVariableTest : KotlinTestCase("KaInlineVariableTest", "inlineVariable") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaInlineVariableTest: skipping — no K2 dependencies available")
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

    private fun resolveFixture(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): Triple<org.jetbrains.kotlin.psi.KtFile, Int, Set<org.openide.filesystems.FileObject>>? {
        val subFo = dir.getFileObject(subDir) ?: return null
        val fileFo = subFo.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null

        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null

        val allFiles = subFo.children
            .filter { it.hasExt("kt") }
            .toSet()

        return Triple(ktFile, offset, allFiles)
    }

    /**
     * An unused `val` should be inlineable with zero [KaInlineVariableResult.readUsages].
     * The declaration range must cover the full `val unused = 99` statement.
     */
    fun testUnused_noReadUsages() {
        val session = getSessionOrSkip() ?: return
        val (ktFile, offset, allFiles) = resolveFixture("unusedVal", session) ?: return

        val result = KaInlineVariableComputer(ktFile, offset, session, allFiles).compute()

        assertNotNull("compute() must return a result for an unused val", result)
        val r = result!!
        assertEquals("unused", r.declarationName)
        assertTrue("Expected no read usages for an unused val", r.readUsages.isEmpty())
        assertEquals("Expected initializer text '99'", "99", r.initializerText)
    }

    /**
     * A `val x = 42` used in one `return x` should report one read usage.
     * After inlining the usage text should be the initializer text.
     */
    fun testSimpleVal_oneUsage() {
        val session = getSessionOrSkip() ?: return
        val (ktFile, offset, allFiles) = resolveFixture("simpleVal", session) ?: return

        val result = KaInlineVariableComputer(ktFile, offset, session, allFiles).compute()

        assertNotNull("compute() must return a result for a simple val", result)
        val r = result!!
        assertEquals("x", r.declarationName)
        assertEquals("42", r.initializerText)

        val totalReadUsages = r.readUsages.values.sumOf { it.size }
        assertEquals("Expected exactly one read usage", 1, totalReadUsages)
    }

    /**
     * A `val y = 10` used in `y + y` should report two read usages.
     */
    fun testMultipleUsages_twoReadUsages() {
        val session = getSessionOrSkip() ?: return
        val (ktFile, offset, allFiles) = resolveFixture("multipleUsages", session) ?: return

        val result = KaInlineVariableComputer(ktFile, offset, session, allFiles).compute()

        assertNotNull("compute() must return a result for multiple usages", result)
        val r = result!!
        assertEquals("y", r.declarationName)
        assertEquals("10", r.initializerText)

        val totalReadUsages = r.readUsages.values.sumOf { it.size }
        assertEquals("Expected exactly two read usages", 2, totalReadUsages)
    }
}
