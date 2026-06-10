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
 * Tests for the surviving [IndenterUtil] whitespace predicates.
 */
class IndenterUtilTest : NbTestCase("IndenterUtilTest") {

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
