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
 *
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.highlighter

import junit.framework.TestCase
import org.netbeans.modules.csl.api.ColoringAttributes

/**
 * Unit tests for [KaHighlightColorMapper].
 *
 * Tests the [KaHighlightColorMapper.toColoringAttributes] overload that accepts a
 * [TextAttributesKey][com.intellij.openapi.editor.colors.TextAttributesKey] external name
 * string directly, avoiding the need to construct IntelliJ infrastructure objects.
 */
class KaHighlightColorMapperTest : TestCase() {

    /** A regular function call maps to METHOD. */
    fun testRegularFunctionCallMapsToMethod() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_FUNCTION_CALL")
        assertEquals(setOf(ColoringAttributes.METHOD), result)
    }

    /** A package-level function call maps to METHOD + STATIC. */
    fun testPackageFunctionCallMapsToMethodAndStatic() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_PACKAGE_FUNCTION_CALL")
        assertEquals(setOf(ColoringAttributes.METHOD, ColoringAttributes.STATIC), result)
    }

    /** An extension function call maps to METHOD + STATIC. */
    fun testExtensionFunctionCallMapsToMethodAndStatic() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_EXTENSION_FUNCTION_CALL")
        assertEquals(setOf(ColoringAttributes.METHOD, ColoringAttributes.STATIC), result)
    }

    /** A suspend function call maps to METHOD + CUSTOM1. */
    fun testSuspendFunctionCallMapsToMethodAndCustom1() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_SUSPEND_FUNCTION_CALL")
        assertEquals(setOf(ColoringAttributes.METHOD, ColoringAttributes.CUSTOM1), result)
    }

    /** A class reference maps to CLASS. */
    fun testClassMapsToClass() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_CLASS")
        assertEquals(setOf(ColoringAttributes.CLASS), result)
    }

    /** A trait (interface) reference maps to INTERFACE. */
    fun testTraitMapsToInterface() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_TRAIT")
        assertEquals(setOf(ColoringAttributes.INTERFACE), result)
    }

    /** An instance property maps to FIELD + ENUM (bold); MUTABLE_VARIABLE overlay adds CUSTOM2 for var. */
    fun testInstancePropertyMapsToFieldAndEnum() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_INSTANCE_PROPERTY")
        assertEquals(setOf(ColoringAttributes.FIELD, ColoringAttributes.ENUM), result)
    }

    /** A function declaration maps to DECLARATION. */
    fun testFunctionDeclarationMapsToDeclaration() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_FUNCTION_DECLARATION")
        assertEquals(setOf(ColoringAttributes.DECLARATION), result)
    }

    /** The mutable-variable overlay maps to CUSTOM2 only (merged on top of base property color). */
    fun testMutableVariableOverlayMapsToCustom2() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_MUTABLE_VARIABLE")
        assertEquals(setOf(ColoringAttributes.CUSTOM2), result)
    }

    /** An unknown key returns null without throwing. */
    fun testUnknownKeyReturnsNull() {
        val result = KaHighlightColorMapper.toColoringAttributes("KOTLIN_UNKNOWN_FUTURE_TOKEN")
        assertNull(result)
    }
}
