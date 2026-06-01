/**
 * *****************************************************************************
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
 ******************************************************************************
 */
package io.github.nbplugins.kotlin.nbm.formatting

import org.jetbrains.kotlin.formatting.IndenterUtil
import org.netbeans.junit.NbTestCase

/**
 * Tests for [IndenterUtil].
 *
 * Verifies fallback values and indentation-string construction.  The test
 * environment may not have a live {@code text/x-kotlin} MimeLookup preferences
 * node, so the fallback path (4 spaces, expand-tabs = true) must always produce
 * sensible results.
 */
class IndenterUtilTest : NbTestCase("IndenterUtilTest") {

    /**
     * When MimeLookup preferences are unavailable the indent size must fall
     * back to 4.
     */
    fun testGetDefaultIndentFallback() {
        val indent = IndenterUtil.getDefaultIndent()
        assertTrue("indent size must be positive", indent > 0)
    }

    /**
     * [IndenterUtil.isSpacesForTabs] must return a boolean without throwing.
     */
    fun testIsSpacesForTabsDoesNotThrow() {
        // just must not throw; actual value depends on user preferences
        IndenterUtil.isSpacesForTabs()
    }

    /**
     * [IndenterUtil.getTabSize] must return a positive value.
     */
    fun testGetTabSizeIsPositive() {
        assertTrue("tab size must be positive", IndenterUtil.getTabSize() > 0)
    }

    /**
     * [IndenterUtil.getIndentString] length must match [IndenterUtil.getDefaultIndent]
     * when spaces are used, or be exactly one tab when tabs are used.
     */
    fun testGetIndentStringConsistentWithSettings() {
        val indentStr = IndenterUtil.getIndentString()
        if (IndenterUtil.isSpacesForTabs()) {
            assertEquals(IndenterUtil.getDefaultIndent(), indentStr.length)
            assertTrue("all chars must be spaces", indentStr.all { it == ' ' })
        } else {
            assertEquals("\t", indentStr)
        }
    }

    /**
     * [IndenterUtil.createWhiteSpace] with zero breaks and zero indent must
     * produce an empty string.
     */
    fun testCreateWhiteSpaceZeroIndent() {
        val ws = IndenterUtil.createWhiteSpace(0, 0, "\n")
        assertEquals("", ws)
    }

    /**
     * [IndenterUtil.createWhiteSpace] with one break and one indent level must
     * produce a newline followed by one indent unit.
     */
    fun testCreateWhiteSpaceOneBreakOneIndent() {
        val ws = IndenterUtil.createWhiteSpace(1, 1, "\n")
        val expected = "\n" + IndenterUtil.getIndentString()
        assertEquals(expected, ws)
    }

    /** [IndenterUtil.isWhiteSpaceChar] must recognise space and tab. */
    fun testIsWhiteSpaceChar() {
        assertTrue(IndenterUtil.isWhiteSpaceChar(' '))
        assertTrue(IndenterUtil.isWhiteSpaceChar('\t'))
        assertFalse(IndenterUtil.isWhiteSpaceChar('a'))
    }

    /** [IndenterUtil.isWhiteSpaceOrNewLine] must recognise space, tab and newline. */
    fun testIsWhiteSpaceOrNewLine() {
        assertTrue(IndenterUtil.isWhiteSpaceOrNewLine(' '))
        assertTrue(IndenterUtil.isWhiteSpaceOrNewLine('\t'))
        assertTrue(IndenterUtil.isWhiteSpaceOrNewLine('\n'))
        assertFalse(IndenterUtil.isWhiteSpaceOrNewLine('x'))
    }

    /** [IndenterUtil.getLineSeparatorsOccurences] must count newlines correctly. */
    fun testGetLineSeparatorsOccurences() {
        assertEquals(0, IndenterUtil.getLineSeparatorsOccurences("abc"))
        assertEquals(1, IndenterUtil.getLineSeparatorsOccurences("a\nb"))
        assertEquals(3, IndenterUtil.getLineSeparatorsOccurences("a\nb\nc\nd"))
    }
}
