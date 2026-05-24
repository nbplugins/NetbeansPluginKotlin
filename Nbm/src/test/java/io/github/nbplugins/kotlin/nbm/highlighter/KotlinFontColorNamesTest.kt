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
 * Verifies that `FontAndColors.xml` (light) and `FontAndColorsDark.xml` (dark) both define
 * the complete set of expected Kotlin color categories.
 *
 * Guards against accidental omissions when adding or renaming categories — a missing entry
 * causes the category to silently receive no color at runtime.
 */
class KotlinFontColorNamesTest : TestCase() {

    private val expectedNames = setOf(
        // Lexical
        "IDENTIFIER", "KEYWORD", "KOTLIN_NUMBER", "KOTLIN_OPERATION_SIGN",
        "KOTLIN_PARENTHESIS", "KOTLIN_BRACES", "KOTLIN_FUNCTION_LITERAL_BRACES_AND_ARROW",
        "KOTLIN_BRACKETS", "KOTLIN_COMMA", "KOTLIN_SEMICOLON", "KOTLIN_DOT",
        "KOTLIN_SAFE_ACCESS", "KOTLIN_ARROW",
        "STRING", "KOTLIN_STRING_ESCAPE", "KOTLIN_INVALID_STRING_ESCAPE",
        "SINGLE_LINE_COMMENT", "MULTI_LINE_COMMENT", "KOTLIN_DOC_COMMENT",
        "KDOC_TAG_NAME", "KDOC_LINK", "ANNOTATION", "WHITESPACE",
        // Classes
        "KOTLIN_CLASS", "KOTLIN_ABSTRACT_CLASS", "KOTLIN_TRAIT", "KOTLIN_OBJECT",
        "KOTLIN_ENUM", "KOTLIN_TYPE_ALIAS", "KOTLIN_ANNOTATION", "KOTLIN_TYPE_PARAMETER",
        // Functions
        "KOTLIN_FUNCTION_DECLARATION", "KOTLIN_FUNCTION_CALL", "KOTLIN_PACKAGE_FUNCTION_CALL",
        "KOTLIN_EXTENSION_FUNCTION_CALL", "KOTLIN_CONSTRUCTOR_CALL", "KOTLIN_CONSTRUCTOR",
        "KOTLIN_SUSPEND_FUNCTION_CALL", "KOTLIN_DYNAMIC_FUNCTION_CALL",
        "KOTLIN_VARIABLE_AS_FUNCTION_CALL", "KOTLIN_VARIABLE_AS_FUNCTION_LIKE_CALL",
        // Variables
        "KOTLIN_LOCAL_VARIABLE", "KOTLIN_PARAMETER", "KOTLIN_WRAPPED_INTO_REF",
        "KOTLIN_INSTANCE_PROPERTY", "KOTLIN_PACKAGE_PROPERTY", "KOTLIN_EXTENSION_PROPERTY",
        "KOTLIN_SYNTHETIC_EXTENSION_PROPERTY", "KOTLIN_BACKING_FIELD_VARIABLE",
        "KOTLIN_ENUM_ENTRY", "KOTLIN_INSTANCE_PROPERTY_CUSTOM_PROPERTY_DECLARATION",
        "KOTLIN_PACKAGE_PROPERTY_CUSTOM_PROPERTY_DECLARATION", "KOTLIN_MUTABLE_VARIABLE",
        "KOTLIN_CLOSURE_DEFAULT_PARAMETER", "KOTLIN_FUNCTION_LITERAL_DEFAULT_PARAMETER",
        "KOTLIN_DYNAMIC_PROPERTY_CALL",
        // Smart casts
        "KOTLIN_SMART_CAST_VALUE", "KOTLIN_SMART_CONSTANT", "KOTLIN_SMART_CAST_RECEIVER",
        // Misc
        "KOTLIN_LABEL", "KOTLIN_NAMED_ARGUMENT", "KOTLIN_ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES",
        "KOTLIN_PROPERTY_WITH_BACKING_FIELD", "KOTLIN_COROUTINE_DEBUGGER_VALUE",
        // Diagnostics
        "KOTLIN_DEPRECATED", "KOTLIN_RESOLVED_TO_ERROR", "KOTLIN_DEBUG_INFO",
        // Infrastructure
        "mark-occurrences"
    )

    /** Light theme file contains all expected categories. */
    fun testLightThemeHasAllExpectedNames() {
        val registered = parseNames("/io/github/nbplugins/kotlin/nbm/FontAndColors.xml")
        val missing = expectedNames - registered
        assertTrue("FontAndColors.xml is missing: $missing", missing.isEmpty())
    }

    /** Dark theme file contains all expected categories. */
    fun testDarkThemeHasAllExpectedNames() {
        val registered = parseNames("/io/github/nbplugins/kotlin/nbm/FontAndColorsDark.xml")
        val missing = expectedNames - registered
        assertTrue("FontAndColorsDark.xml is missing: $missing", missing.isEmpty())
    }

    /** Both theme files define exactly the same set of category names. */
    fun testLightAndDarkThemeHaveSameNames() {
        val light = parseNames("/io/github/nbplugins/kotlin/nbm/FontAndColors.xml")
        val dark = parseNames("/io/github/nbplugins/kotlin/nbm/FontAndColorsDark.xml")
        val onlyInLight = light - dark
        val onlyInDark = dark - light
        assertTrue(
            "Name mismatch between light and dark themes. Only in light: $onlyInLight. Only in dark: $onlyInDark",
            onlyInLight.isEmpty() && onlyInDark.isEmpty()
        )
    }

    /**
     * Every color category declared in the theme files must have a display name in
     * `Bundle.properties`. A missing entry makes the Fonts & Colors panel show the raw
     * internal name (e.g. `KOTLIN_SMART_CAST_VALUE`) instead of the IDEA-style grouped label.
     * `mark-occurrences` is infrastructure and intentionally has no Kotlin display name.
     */
    fun testEveryCategoryHasDisplayName() {
        val displayNames = parseBundleKeys()
        val categories = parseNames("/io/github/nbplugins/kotlin/nbm/FontAndColors.xml") - "mark-occurrences"
        val missing = categories - displayNames
        assertTrue("Bundle.properties is missing display names for: $missing", missing.isEmpty())
    }

    private fun parseBundleKeys(): Set<String> {
        val text = KotlinFontColorNamesTest::class.java
            .getResourceAsStream("/io/github/nbplugins/kotlin/nbm/Bundle.properties")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("Bundle.properties must be on the classpath", text)
        return text!!.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .map { it.substringBefore("=").trim() }
            .toSet()
    }

    private fun parseNames(resourcePath: String): Set<String> {
        val xml = KotlinFontColorNamesTest::class.java
            .getResourceAsStream(resourcePath)
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("$resourcePath must be on the classpath", xml)
        return Regex("""<fontcolor\s+name="([^"]+)"""").findAll(xml!!)
            .map { it.groupValues[1] }
            .toSet()
    }
}
