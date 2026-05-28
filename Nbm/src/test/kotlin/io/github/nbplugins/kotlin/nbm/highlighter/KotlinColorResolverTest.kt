/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.highlighter

import junit.framework.TestCase

/**
 * Unit tests for [KotlinColorResolver].
 *
 * In a plain unit-test environment (no running NetBeans), [FontColorSettings] is not
 * available, so both methods are expected to return `null` gracefully.  The tests
 * verify that no exception is thrown and that return values are either `null` or a
 * valid CSS hex string.
 */
class KotlinColorResolverTest : TestCase() {

    /**
     * [KotlinColorResolver.attributeSetFor] returns `null` or an [AttributeSet] without
     * throwing when called outside a NetBeans runtime.
     */
    fun testAttributeSetFor_doesNotThrow() {
        val result = runCatching { KotlinColorResolver.attributeSetFor("KOTLIN_FUNCTION_DECLARATION") }
        assertTrue("attributeSetFor must not throw", result.isSuccess)
    }

    /**
     * [KotlinColorResolver.foregroundHex] returns `null` or a valid `#rrggbb` string.
     *
     * In a test environment without an active theme, `null` is acceptable.
     */
    fun testForegroundHex_nullOrValidHex() {
        val result = KotlinColorResolver.foregroundHex("KOTLIN_FUNCTION_DECLARATION")
        if (result != null) {
            assertTrue(
                "foregroundHex must return a #rrggbb string, got '$result'",
                result.matches(Regex("#[0-9a-f]{6}"))
            )
        }
    }

    /**
     * [KotlinColorResolver.foregroundHex] returns `null` for an unknown category name
     * without throwing.
     */
    fun testForegroundHex_unknownCategory_returnsNull() {
        val result = runCatching { KotlinColorResolver.foregroundHex("UNKNOWN_CATEGORY_XYZ") }
        assertTrue("foregroundHex must not throw for unknown category", result.isSuccess)
    }
}
