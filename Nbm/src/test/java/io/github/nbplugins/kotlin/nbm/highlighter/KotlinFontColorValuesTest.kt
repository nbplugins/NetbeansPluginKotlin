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
 * Verifies the actual `foreColor` / `bgColor` values declared in the theme color files match
 * the colors resolved from IntelliJ IDEA's color schemes:
 *
 * - **FlatLafLight** = IntelliJ "Light" scheme (`themes/Light.xml`, parent `Default`) + Kotlin fallback
 *   chain in `KotlinHighlightingColors.java` + explicit colors in `colorScheme/Default_Kotlin.xml`.
 * - **FlatLafDark** = IntelliJ "Darcula" scheme + `colorScheme/Darcula_Kotlin.xml`.
 * - **NetBeans55** = IntelliJ "Classic Light" (`Default`) scheme + `colorScheme/Default_Kotlin.xml`.
 *
 * Guards against silent drift of the resolved hex values (e.g. accidentally reverting to the
 * Classic "Default" scheme for FlatLafLight, where keyword was `000080` instead of `0033B3`).
 */
class KotlinFontColorValuesTest : TestCase() {

    /** Parsed `<fontcolor>` element: foreground / background hex (uppercased, no leading '#'). */
    private data class FontColor(val foreColor: String?, val bgColor: String?)

    /** Expected foreground colors for the light (IntelliJ Light) theme. */
    private val expectedLightFore = mapOf(
        "KEYWORD" to "0033B3",
        "KOTLIN_NUMBER" to "1750EB",
        "STRING" to "067D17",
        "KOTLIN_STRING_ESCAPE" to "0037A6",
        "SINGLE_LINE_COMMENT" to "8C8C8C",
        "MULTI_LINE_COMMENT" to "8C8C8C",
        "KOTLIN_DOC_COMMENT" to "8C8C8C",
        "KDOC_LINK" to "3D3D3D",
        "ANNOTATION" to "9E880D",
        "KOTLIN_ANNOTATION" to "9E880D",
        "KOTLIN_TYPE_PARAMETER" to "007E8A",
        "KOTLIN_INSTANCE_PROPERTY" to "871094",
        "KOTLIN_PACKAGE_PROPERTY" to "871094",
        "KOTLIN_EXTENSION_PROPERTY" to "871094",
        "KOTLIN_ENUM_ENTRY" to "871094",
        "KOTLIN_FUNCTION_DECLARATION" to "00627A",
        "KOTLIN_PACKAGE_FUNCTION_CALL" to "00627A",
        "KOTLIN_EXTENSION_FUNCTION_CALL" to "00627A",
        "KOTLIN_LABEL" to "4A86E8",
        "KOTLIN_NAMED_ARGUMENT" to "4A86E8",
        "KOTLIN_COROUTINE_DEBUGGER_VALUE" to "840000",
    )

    /** Expected foreground colors for the dark (Darcula) theme. */
    private val expectedDarkFore = mapOf(
        "KEYWORD" to "CC7832",
        "KOTLIN_NUMBER" to "6897BB",
        "STRING" to "6A8759",
        "KOTLIN_STRING_ESCAPE" to "CC7832",
        "ANNOTATION" to "BBB529",
        "KOTLIN_ANNOTATION" to "BBB529",
        "KOTLIN_INSTANCE_PROPERTY" to "9876AA",
        "KOTLIN_PACKAGE_PROPERTY" to "9876AA",
        "KOTLIN_FUNCTION_DECLARATION" to "FFC66D",
        "KOTLIN_PACKAGE_FUNCTION_CALL" to "FFC66D",
        "KOTLIN_EXTENSION_FUNCTION_CALL" to "FFC66D",
        "KOTLIN_LABEL" to "467CDA",
        "KOTLIN_COROUTINE_DEBUGGER_VALUE" to "FF8C8B",
    )

    /** Expected foreground colors for the NetBeans 5.5 (Classic Light / Default) theme. */
    private val expectedNB55Fore = mapOf(
        "KEYWORD" to "000080",
        "KOTLIN_NUMBER" to "0000FF",
        "STRING" to "008000",
        "KOTLIN_STRING_ESCAPE" to "000080",
        "SINGLE_LINE_COMMENT" to "808080",
        "MULTI_LINE_COMMENT" to "808080",
        "KOTLIN_DOC_COMMENT" to "808080",
        "KDOC_LINK" to "3D3D3D",
        "ANNOTATION" to "808000",
        "KOTLIN_ANNOTATION" to "808000",
        "KOTLIN_INSTANCE_PROPERTY" to "660E7A",
        "KOTLIN_PACKAGE_PROPERTY" to "660E7A",
        "KOTLIN_EXTENSION_PROPERTY" to "660E7A",
        "KOTLIN_ENUM_ENTRY" to "660E7A",
        "KOTLIN_LABEL" to "4A86E8",
        "KOTLIN_NAMED_ARGUMENT" to "4A86E8",
        "KOTLIN_COROUTINE_DEBUGGER_VALUE" to "840000",
    )

    /** Expected background colors (shared light theme — smart casts, backing field). */
    private val expectedLightBg = mapOf(
        "KOTLIN_SMART_CAST_VALUE" to "DBFFDB",
        "KOTLIN_SMART_CONSTANT" to "DBFFDB",
        "KOTLIN_SMART_CAST_RECEIVER" to "DBFFDB",
        "KOTLIN_PROPERTY_WITH_BACKING_FIELD" to "F5D7EF",
        "KOTLIN_INVALID_STRING_ESCAPE" to "FFCCCC",
    )

    /** FlatLafLight foreground colors match IntelliJ Light. */
    fun testLightForegroundValues() {
        val colors = parse("/io/github/nbplugins/kotlin/nbm/highlighter/colors/FlatLafLight.xml")
        assertForeground(colors, expectedLightFore, "FlatLafLight.xml")
    }

    /** FlatLafDark foreground colors match Darcula. */
    fun testDarkForegroundValues() {
        val colors = parse("/io/github/nbplugins/kotlin/nbm/highlighter/colors/FlatLafDark.xml")
        assertForeground(colors, expectedDarkFore, "FlatLafDark.xml")
    }

    /** NetBeans55 foreground colors match Classic Light (Default). */
    fun testNB55ForegroundValues() {
        val colors = parse("/io/github/nbplugins/kotlin/nbm/highlighter/colors/NetBeans55.xml")
        assertForeground(colors, expectedNB55Fore, "NetBeans55.xml")
    }

    /** FlatLafLight background colors (smart casts etc.) match IDEA. */
    fun testLightBackgroundValues() {
        val colors = parse("/io/github/nbplugins/kotlin/nbm/highlighter/colors/FlatLafLight.xml")
        for ((name, expected) in expectedLightBg) {
            val actual = colors[name]?.bgColor?.uppercase()
            assertEquals("bgColor for $name in FlatLafLight.xml", expected, actual)
        }
    }

    /** NetBeans55 background colors (smart casts etc.) match Classic Light defaults. */
    fun testNB55BackgroundValues() {
        val colors = parse("/io/github/nbplugins/kotlin/nbm/highlighter/colors/NetBeans55.xml")
        for ((name, expected) in expectedLightBg) {
            val actual = colors[name]?.bgColor?.uppercase()
            assertEquals("bgColor for $name in NetBeans55.xml", expected, actual)
        }
    }

    /**
     * KEYWORD must NOT be bold in FlatLafLight and FlatLafDark. In IDEA the Kotlin keyword
     * color resolves `KOTLIN_KEYWORD -> JAVA_KEYWORD -> DefaultLanguageHighlighterColors.KEYWORD`,
     * and the IntelliJ Light and Darcula schemes render it plain (bold is set only for the
     * colorblind variants). Guards against re-introducing the incorrect bold style.
     *
     * NetBeans55 uses the Classic Light (Default) scheme where keyword IS bold — excluded here.
     */
    fun testKeywordIsNotBold() {
        for (file in listOf(
            "/io/github/nbplugins/kotlin/nbm/highlighter/colors/FlatLafLight.xml",
            "/io/github/nbplugins/kotlin/nbm/highlighter/colors/FlatLafDark.xml",
        )) {
            val block = fontColorBlock(file, "KEYWORD")
            assertFalse("KEYWORD must not be bold in $file (block: $block)", block.contains("bold"))
        }
    }

    /** KEYWORD must be bold in NetBeans55 — Classic Light (Default) renders it bold. */
    fun testNB55KeywordIsBold() {
        val file = "/io/github/nbplugins/kotlin/nbm/highlighter/colors/NetBeans55.xml"
        val block = fontColorBlock(file, "KEYWORD")
        assertTrue("KEYWORD must be bold in $file (block: $block)", block.contains("bold"))
    }

    /** Extracts the full `<fontcolor name="$name" ...>...</fontcolor>` (or self-closed) element text. */
    private fun fontColorBlock(resourcePath: String, name: String): String {
        val xml = KotlinFontColorValuesTest::class.java
            .getResourceAsStream(resourcePath)
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("$resourcePath must be on the classpath", xml)
        val block = Regex(
            """<fontcolor name="$name".*?(?:/>|</fontcolor>)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml!!)?.value
        assertNotNull("$resourcePath must declare <fontcolor name=\"$name\">", block)
        return block!!
    }

    private fun assertForeground(
        colors: Map<String, FontColor>,
        expected: Map<String, String>,
        file: String,
    ) {
        for ((name, expectedColor) in expected) {
            assertTrue("$file must declare <fontcolor name=\"$name\">", colors.containsKey(name))
            val actual = colors[name]?.foreColor?.uppercase()
            assertEquals("foreColor for $name in $file", expectedColor, actual)
        }
    }

    /** Parses all `<fontcolor>` elements into name -> [FontColor]. */
    private fun parse(resourcePath: String): Map<String, FontColor> {
        val xml = KotlinFontColorValuesTest::class.java
            .getResourceAsStream(resourcePath)
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull("$resourcePath must be on the classpath", xml)
        val nameRe = Regex("""name="([^"]+)"""")
        val foreRe = Regex("""foreColor="([^"]+)"""")
        val bgRe = Regex("""bgColor="([^"]+)"""")
        return Regex("""<fontcolor\b[^>]*?/?>""").findAll(xml!!)
            .mapNotNull { m ->
                val tag = m.value
                val name = nameRe.find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                name to FontColor(
                    foreColor = foreRe.find(tag)?.groupValues?.get(1),
                    bgColor = bgRe.find(tag)?.groupValues?.get(1),
                )
            }
            .toMap()
    }
}
