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
package io.github.nbplugins.kotlin.nbm.highlighter

import junit.framework.TestCase
import io.github.nbplugins.kotlin.nbm.highlighter.TokenType

/**
 * Unit tests for new [TokenType] values added in E2 (lexical category expansion).
 *
 * Verifies that each new token type has the expected integer ID and that the ID values
 * are unique (no accidental aliasing).
 */
class KotlinTokenTypeTest : TestCase() {

    /** Each new token type has the expected numeric ID. */
    fun testNewTokenTypeIds() {
        assertEquals(10, TokenType.NUMBER.getId())
        assertEquals(11, TokenType.OPERATOR.getId())
        assertEquals(12, TokenType.PARENTHESIS.getId())
        assertEquals(13, TokenType.BRACES.getId())
        assertEquals(14, TokenType.BRACKETS.getId())
        assertEquals(15, TokenType.COMMA.getId())
        assertEquals(16, TokenType.SEMICOLON.getId())
        assertEquals(17, TokenType.DOT.getId())
        assertEquals(18, TokenType.ARROW.getId())
        assertEquals(19, TokenType.STRING_ESCAPE.getId())
    }

    /** Pre-existing token IDs are unchanged. */
    fun testExistingTokenTypeIds() {
        assertEquals(0, TokenType.KEYWORD.getId())
        assertEquals(1, TokenType.IDENTIFIER.getId())
        assertEquals(2, TokenType.STRING.getId())
        assertEquals(3, TokenType.SINGLE_LINE_COMMENT.getId())
        assertEquals(4, TokenType.MULTI_LINE_COMMENT.getId())
        assertEquals(5, TokenType.KDOC_TAG_NAME.getId())
        assertEquals(6, TokenType.WHITESPACE.getId())
        assertEquals(7, TokenType.UNDEFINED.getId())
        assertEquals(8, TokenType.ANNOTATION.getId())
        assertEquals(9, TokenType.KDOC_LINK.getId())
    }

    /** No two token types share the same ID. */
    fun testTokenTypeIdsAreUnique() {
        val ids = TokenType.values()
            .filter { it != TokenType.EOF && it != TokenType.UNDEFINED }
            .map { it.getId() }
        assertEquals("Duplicate token type IDs detected", ids.size, ids.toSet().size)
    }
}
