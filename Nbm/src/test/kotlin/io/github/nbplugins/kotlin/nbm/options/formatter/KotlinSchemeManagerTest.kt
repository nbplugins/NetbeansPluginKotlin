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
package io.github.nbplugins.kotlin.nbm.options.formatter

import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.formatter.KotlinOfficialStyleGuide
import org.netbeans.junit.NbTestCase
import java.io.File

/**
 * Tests for [KotlinSchemeManager].
 *
 * <p>Exercises the full CRUD lifecycle (create, load, rename, delete) and
 * the export/import round-trip. Each test uses a uniquely named scheme so
 * that tests do not interfere with each other or with any real user data.
 */
class KotlinSchemeManagerTest : NbTestCase("KotlinSchemeManagerTest") {

    /** Scheme names created during this test; cleaned up in [tearDown]. */
    private val createdSchemes = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    override fun tearDown() {
        super.tearDown()
        createdSchemes.forEach { KotlinSchemeManager.deleteScheme(it) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun uniqueName(base: String = "TestScheme"): String {
        val name = "$base-${System.nanoTime()}"
        createdSchemes.add(name)
        return name
    }

    private fun settingsWithTrailingComma(value: Boolean): CodeStyleSettings =
        CodeStyleSettings().also { it.kotlinCustomSettings.ALLOW_TRAILING_COMMA = value }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /** Verifies that a created scheme can be loaded back with the same settings. */
    fun testCreateAndLoad_roundTrip() {
        val name = uniqueName()
        val settings = settingsWithTrailingComma(true)

        KotlinSchemeManager.createScheme(name, settings, basePresetId = "KOTLIN_OFFICIAL")
        val loaded = KotlinSchemeManager.loadScheme(name)

        assertNotNull("Loaded scheme must not be null", loaded)
        assertTrue("ALLOW_TRAILING_COMMA must survive round-trip",
            loaded!!.kotlinCustomSettings.ALLOW_TRAILING_COMMA)
    }

    /** Verifies that renaming a scheme moves settings to the new name and removes the old. */
    fun testRename_updatesName() {
        val oldName = uniqueName("OldScheme")
        val newName = uniqueName("NewScheme")
        val settings = settingsWithTrailingComma(true)

        KotlinSchemeManager.createScheme(oldName, settings, basePresetId = null)
        KotlinSchemeManager.renameScheme(oldName, newName)

        val names = KotlinSchemeManager.listCustomSchemeNames()
        assertFalse("Old name must be gone after rename", names.contains(oldName))
        assertTrue("New name must appear after rename", names.contains(newName))
        assertTrue("Settings must survive rename",
            KotlinSchemeManager.loadScheme(newName)!!.kotlinCustomSettings.ALLOW_TRAILING_COMMA)
    }

    /** Verifies that a deleted scheme is no longer returned by [KotlinSchemeManager.listCustomSchemeNames]. */
    fun testDelete_removesScheme() {
        val name = uniqueName()
        KotlinSchemeManager.createScheme(name, CodeStyleSettings(), basePresetId = null)

        KotlinSchemeManager.deleteScheme(name)

        assertFalse("Scheme must not appear after deletion",
            KotlinSchemeManager.listCustomSchemeNames().contains(name))
    }

    /** Verifies that [KotlinSchemeManager.getBasePresetId] returns the stored preset ID. */
    fun testGetBasePresetId_returnsStoredId() {
        val name = uniqueName()
        KotlinSchemeManager.createScheme(name, CodeStyleSettings(), basePresetId = "KOTLIN_OFFICIAL")

        assertEquals("KOTLIN_OFFICIAL", KotlinSchemeManager.getBasePresetId(name))
    }

    /** Verifies that a null base preset ID is correctly stored and retrieved. */
    fun testGetBasePresetId_nullForDefaults() {
        val name = uniqueName()
        KotlinSchemeManager.createScheme(name, CodeStyleSettings(), basePresetId = null)

        assertNull("Base preset ID must be null for IDE Defaults", KotlinSchemeManager.getBasePresetId(name))
    }

    /** Verifies that export to XML and import back preserves the scheme name and settings. */
    fun testExportImport_roundTrip() {
        val name = uniqueName("ExportTestScheme")
        val settings = settingsWithTrailingComma(true).also {
            it.getIndentOptions().INDENT_SIZE = 2
        }
        val tmpFile = File.createTempFile("kotlin-scheme-test-", ".xml").also { it.deleteOnExit() }

        KotlinSchemeManager.exportToXml(name, settings, tmpFile)
        val result = KotlinSchemeManager.importFromXml(tmpFile)

        assertNotNull("Import must succeed", result)
        val (importedName, importedSettings) = result!!
        assertEquals("Scheme name must survive export/import", name, importedName)
        assertTrue("ALLOW_TRAILING_COMMA must survive export/import",
            importedSettings.kotlinCustomSettings.ALLOW_TRAILING_COMMA)
        assertEquals("INDENT_SIZE must survive export/import",
            2, importedSettings.getIndentOptions().INDENT_SIZE)
    }

    /** Verifies that listing returns schemes in alphabetical order. */
    fun testListCustomSchemeNames_sortedAlphabetically() {
        val prefix = "ZZZ-sort-test-${System.nanoTime()}-"
        val names = listOf("${prefix}Gamma", "${prefix}Alpha", "${prefix}Beta")
        names.forEach {
            createdSchemes.add(it)
            KotlinSchemeManager.createScheme(it, CodeStyleSettings(), basePresetId = null)
        }

        val listed = KotlinSchemeManager.listCustomSchemeNames()
            .filter { it.startsWith(prefix) }

        assertEquals(listOf("${prefix}Alpha", "${prefix}Beta", "${prefix}Gamma"), listed)
    }

    /** Verifies that [KotlinSchemeManager.updateScheme] overwrites settings in an existing scheme. */
    fun testUpdateScheme_overwritesSettings() {
        val name = uniqueName()
        KotlinSchemeManager.createScheme(name, settingsWithTrailingComma(false), basePresetId = null)
        KotlinSchemeManager.updateScheme(name, settingsWithTrailingComma(true))

        assertTrue("Updated ALLOW_TRAILING_COMMA must persist",
            KotlinSchemeManager.loadScheme(name)!!.kotlinCustomSettings.ALLOW_TRAILING_COMMA)
    }

    /** Verifies that importing a malformed XML file returns {@code null} without throwing. */
    fun testImportFromXml_malformedFile_returnsNull() {
        val tmpFile = File.createTempFile("kotlin-scheme-bad-", ".xml").also {
            it.writeText("<<<not valid xml>>>")
            it.deleteOnExit()
        }
        val result = KotlinSchemeManager.importFromXml(tmpFile)
        assertNull("Import of malformed XML must return null", result)
    }

    /** Verifies that loading a non-existent scheme returns {@code null}. */
    fun testLoadScheme_nonExistent_returnsNull() {
        val result = KotlinSchemeManager.loadScheme("does-not-exist-${System.nanoTime()}")
        assertNull("Loading a non-existent scheme must return null", result)
    }
}
