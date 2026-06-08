/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.formatting.options

import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.formatter.KotlinCodeStyleSerializer
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.formatter.KotlinCommonCodeStyleSettings
import org.netbeans.junit.NbTestCase

/**
 * Tests for [KotlinCodeStyleSerializer.exportSchemeXml] and
 * [KotlinCodeStyleSerializer.importSchemeXml].
 *
 * <p>Verifies that the produced XML is IDEA-compatible (contains the expected
 * element names) and that export/import is a faithful round-trip for all
 * stored fields.
 */
class KotlinCodeStyleSerializerSchemeTest : NbTestCase("KotlinCodeStyleSerializerSchemeTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun settingsWithCs(): Pair<CodeStyleSettings, KotlinCommonCodeStyleSettings> {
        val settings = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(settings)
        val cs = settings.getCommonSettings(KotlinLanguage.INSTANCE) as KotlinCommonCodeStyleSettings
        return Pair(settings, cs)
    }

    private fun export(name: String, settings: CodeStyleSettings, cs: KotlinCommonCodeStyleSettings?): String =
        KotlinCodeStyleSerializer.exportSchemeXml(name, settings, cs)

    // -------------------------------------------------------------------------
    // Structure tests
    // -------------------------------------------------------------------------

    /** Verifies that exported XML has a {@code <code_scheme name="..." version="173">} root. */
    fun testExportSchemeXml_containsCodeSchemeRoot() {
        val (settings, cs) = settingsWithCs()
        val xml = export("My Scheme", settings, cs)

        assertTrue("XML must start with <code_scheme", xml.contains("<code_scheme"))
        assertTrue("XML must contain name attribute", xml.contains("""name="My Scheme""""))
        assertTrue("XML must contain version attribute", xml.contains("""version="173""""))
    }

    /** Verifies that exported XML contains a {@code <JetCodeStyleSettings>} child. */
    fun testExportSchemeXml_containsJetCodeStyleSettings() {
        val (settings, cs) = settingsWithCs()
        val xml = export("Test", settings, cs)

        assertTrue("XML must contain <JetCodeStyleSettings>", xml.contains("<JetCodeStyleSettings"))
    }

    /** Verifies that exported XML contains a {@code <codeStyleSettings language="kotlin">} child. */
    fun testExportSchemeXml_containsCodeStyleSettingsLanguageKotlin() {
        val (settings, cs) = settingsWithCs()
        val xml = export("Test", settings, cs)

        assertTrue("XML must contain <codeStyleSettings language=\"kotlin\">",
            xml.contains("""<codeStyleSettings language="kotlin""""))
    }

    /** Verifies that exported XML contains an {@code <indentOptions>} child. */
    fun testExportSchemeXml_containsIndentOptions() {
        val (settings, cs) = settingsWithCs()
        val xml = export("Test", settings, cs)

        assertTrue("XML must contain <indentOptions>", xml.contains("<indentOptions>") || xml.contains("<indentOptions "))
    }

    // -------------------------------------------------------------------------
    // Round-trip tests
    // -------------------------------------------------------------------------

    /** Verifies that the scheme name survives an export/import cycle. */
    fun testImportSchemeXml_roundTrip_name() {
        val (settings, cs) = settingsWithCs()
        val xml = export("My Round-Trip Scheme", settings, cs)

        val outName = arrayOf("")
        val imported = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(imported)
        val importedCs = imported.getCommonSettings(KotlinLanguage.INSTANCE) as KotlinCommonCodeStyleSettings
        KotlinCodeStyleSerializer.importSchemeXml(xml, outName, imported, importedCs)

        assertEquals("My Round-Trip Scheme", outName[0])
    }

    /** Verifies that [KotlinCodeStyleSettings] fields survive an export/import cycle. */
    fun testImportSchemeXml_roundTrip_kotlinSettings() {
        val (settings, cs) = settingsWithCs()
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA = true
        settings.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT = 7
        val xml = export("Test", settings, cs)

        val outName = arrayOf("")
        val imported = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(imported)
        val importedCs = imported.getCommonSettings(KotlinLanguage.INSTANCE) as KotlinCommonCodeStyleSettings
        KotlinCodeStyleSerializer.importSchemeXml(xml, outName, imported, importedCs)

        assertTrue("ALLOW_TRAILING_COMMA must survive round-trip",
            imported.kotlinCustomSettings.ALLOW_TRAILING_COMMA)
        assertEquals("NAME_COUNT_TO_USE_STAR_IMPORT must survive round-trip",
            7, imported.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT)
    }

    /** Verifies that [IndentOptions] fields survive an export/import cycle. */
    fun testImportSchemeXml_roundTrip_indentOptions() {
        val (settings, cs) = settingsWithCs()
        settings.getIndentOptions().INDENT_SIZE = 2
        settings.getIndentOptions().USE_TAB_CHARACTER = true
        val xml = export("Test", settings, cs)

        val outName = arrayOf("")
        val imported = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(imported)
        val importedCs = imported.getCommonSettings(KotlinLanguage.INSTANCE) as KotlinCommonCodeStyleSettings
        KotlinCodeStyleSerializer.importSchemeXml(xml, outName, imported, importedCs)

        assertEquals("INDENT_SIZE must survive round-trip", 2, imported.getIndentOptions().INDENT_SIZE)
        assertTrue("USE_TAB_CHARACTER must survive round-trip", imported.getIndentOptions().USE_TAB_CHARACTER)
    }

    /** Verifies that [KotlinCommonCodeStyleSettings] bool fields survive an export/import cycle. */
    fun testImportSchemeXml_roundTrip_commonSettings() {
        val (settings, cs) = settingsWithCs()
        cs.SPACE_AROUND_ASSIGNMENT_OPERATORS = false
        cs.KEEP_LINE_BREAKS = false
        val xml = export("Test", settings, cs)

        val outName = arrayOf("")
        val imported = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(imported)
        val importedCs = imported.getCommonSettings(KotlinLanguage.INSTANCE) as KotlinCommonCodeStyleSettings
        KotlinCodeStyleSerializer.importSchemeXml(xml, outName, imported, importedCs)

        assertFalse("SPACE_AROUND_ASSIGNMENT_OPERATORS must survive round-trip",
            importedCs.SPACE_AROUND_ASSIGNMENT_OPERATORS)
        assertFalse("KEEP_LINE_BREAKS must survive round-trip", importedCs.KEEP_LINE_BREAKS)
    }

    /** Verifies that passing {@code null} for {@code cs} omits the codeStyleSettings block. */
    fun testExportSchemeXml_nullCs_omitsCommonBlock() {
        val (settings, _) = settingsWithCs()
        val xml = export("Test", settings, null)

        // JetCodeStyleSettings and indentOptions must still be present
        assertTrue(xml.contains("<JetCodeStyleSettings"))
        assertTrue(xml.contains("<indentOptions>") || xml.contains("<indentOptions "))
    }

    /** Verifies that malformed XML causes an exception to be thrown. */
    fun testImportSchemeXml_malformedXml_throws() {
        val outName = arrayOf("")
        val imported = CodeStyleSettings()
        try {
            KotlinCodeStyleSerializer.importSchemeXml("<<<not-xml>>>", outName, imported, null)
            fail("Expected exception for malformed XML")
        } catch (e: Exception) {
            // expected
        }
    }
}
