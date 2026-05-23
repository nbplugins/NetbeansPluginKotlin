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

/**
 * Unit tests for [KaHighlightColorMapper].
 *
 * Tests the [KaHighlightColorMapper.toFontColorName] overload that accepts a
 * [TextAttributesKey][com.intellij.openapi.editor.colors.TextAttributesKey] external name
 * string directly, avoiding the need to construct IntelliJ infrastructure objects.
 */
class KaHighlightColorMapperTest : TestCase() {

    /** A regular function call maps to KOTLIN_FUNCTION_CALL. */
    fun testRegularFunctionCallMapsToName() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_FUNCTION_CALL")
        assertEquals("KOTLIN_FUNCTION_CALL", result)
    }

    /** A package-level function call maps to KOTLIN_PACKAGE_FUNCTION_CALL. */
    fun testPackageFunctionCallMapsToName() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_PACKAGE_FUNCTION_CALL")
        assertEquals("KOTLIN_PACKAGE_FUNCTION_CALL", result)
    }

    /** A suspend function call maps to KOTLIN_SUSPEND_FUNCTION_CALL. */
    fun testSuspendFunctionCallMapsToName() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_SUSPEND_FUNCTION_CALL")
        assertEquals("KOTLIN_SUSPEND_FUNCTION_CALL", result)
    }

    /** A class reference maps to KOTLIN_CLASS. */
    fun testClassMapsToName() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_CLASS")
        assertEquals("KOTLIN_CLASS", result)
    }

    /** A trait (interface) reference maps to KOTLIN_TRAIT. */
    fun testTraitMapsToName() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_TRAIT")
        assertEquals("KOTLIN_TRAIT", result)
    }

    /** An instance property maps to KOTLIN_INSTANCE_PROPERTY. */
    fun testInstancePropertyMapsToName() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_INSTANCE_PROPERTY")
        assertEquals("KOTLIN_INSTANCE_PROPERTY", result)
    }

    /** A function declaration maps to KOTLIN_FUNCTION_DECLARATION. */
    fun testFunctionDeclarationMapsToName() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_FUNCTION_DECLARATION")
        assertEquals("KOTLIN_FUNCTION_DECLARATION", result)
    }

    /** The mutable-variable overlay maps to KOTLIN_MUTABLE_VARIABLE. */
    fun testMutableVariableMapsToName() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_MUTABLE_VARIABLE")
        assertEquals("KOTLIN_MUTABLE_VARIABLE", result)
    }

    /** An unknown key returns null without throwing. */
    fun testUnknownKeyReturnsNull() {
        val result = KaHighlightColorMapper.toFontColorName("KOTLIN_UNKNOWN_FUTURE_TOKEN")
        assertNull(result)
    }

    /**
     * Every color category name the mapper can emit must have a matching `<fontcolor name="...">` entry
     * in `FontAndColors.xml`. Missing entries would cause categories to be silently uncolored.
     */
    fun testEveryEmittedColorNameIsRegisteredInLightTheme() {
        val registered = registeredColorNames("/io/github/nbplugins/kotlin/nbm/FontAndColors.xml")
        val missing = KaHighlightColorMapper.emittedColorNames.filter { it !in registered }
        assertTrue(
            "FontAndColors.xml is missing color registrations for: $missing",
            missing.isEmpty()
        )
    }

    /**
     * Every color category name must also be registered in the dark theme file.
     */
    fun testEveryEmittedColorNameIsRegisteredInDarkTheme() {
        val registered = registeredColorNames("/io/github/nbplugins/kotlin/nbm/FontAndColorsDark.xml")
        val missing = KaHighlightColorMapper.emittedColorNames.filter { it !in registered }
        assertTrue(
            "FontAndColorsDark.xml is missing color registrations for: $missing",
            missing.isEmpty()
        )
    }

    /** Reads all `<fontcolor name="…">` names declared in the given classpath resource. */
    private fun registeredColorNames(resourcePath: String): Set<String> {
        val xml = KaHighlightColorMapperTest::class.java
            .getResourceAsStream(resourcePath)
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("$resourcePath must be on the classpath", xml)
        return Regex("""<fontcolor\s+name="([^"]+)"""").findAll(xml!!)
            .map { it.groupValues[1] }
            .toSet()
    }
}
