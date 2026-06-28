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

import org.netbeans.junit.NbTestCase
import javax.swing.text.DefaultStyledDocument

/**
 * Unit tests for [KotlinExtractFunctionPlugin] and [KotlinExtractFunctionRefactoring].
 *
 * These tests cover the contract and guard conditions of the plugin layer without
 * requiring the full K2 analysis session.  End-to-end extraction correctness is verified
 * by [KaExtractFunctionTest] using real K2 sessions.
 */
class KotlinExtractFunctionPluginTest : NbTestCase("KotlinExtractFunctionPluginTest") {

    /**
     * Verifies that the [KotlinExtractFunctionRefactoring] carrier stores its constructor
     * arguments correctly and that [KotlinExtractFunctionRefactoring.chosenName] defaults to
     * an empty string.
     */
    fun testRefactoringCarrier_storesFieldsCorrectly() {
        val doc = DefaultStyledDocument()
        doc.insertString(0, "fun foo() {}", null)

        val refactoring = KotlinExtractFunctionRefactoring(doc, 12, 20)

        assertEquals("startOffset stored correctly", 12, refactoring.startOffset)
        assertEquals("endOffset stored correctly", 20, refactoring.endOffset)
        assertSame("doc stored correctly", doc, refactoring.doc)
        assertEquals("chosenName defaults to empty string", "", refactoring.chosenName)
    }

    /**
     * Verifies that setting [KotlinExtractFunctionRefactoring.chosenName] persists the value.
     */
    fun testRefactoringCarrier_chosenNameSetterWorks() {
        val doc = DefaultStyledDocument()
        val refactoring = KotlinExtractFunctionRefactoring(doc, 0, 5)
        refactoring.chosenName = "myExtracted"

        assertEquals("chosenName updated correctly", "myExtracted", refactoring.chosenName)
    }

    /**
     * Verifies that the plugin lifecycle hook methods complete without throwing and return `null`.
     */
    fun testPlugin_lifecycleHooks_doNotThrow() {
        val doc = DefaultStyledDocument()
        val refactoring = KotlinExtractFunctionRefactoring(doc, 0, 0)
        val plugin = KotlinExtractFunctionPlugin(refactoring)

        assertNull("preCheck() returns null", plugin.preCheck())
        assertNull("fastCheckParameters() returns null", plugin.fastCheckParameters())
        assertNull("checkParameters() returns null", plugin.checkParameters())
        plugin.cancelRequest()  // must not throw
    }
}
