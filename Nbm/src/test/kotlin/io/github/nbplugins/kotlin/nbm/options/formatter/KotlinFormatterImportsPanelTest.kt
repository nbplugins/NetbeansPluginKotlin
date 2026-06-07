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
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntry
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Tests for [KotlinFormatterImportsPanel].
 *
 * <p>Verifies that controls reflect KOTLIN_OFFICIAL defaults when loaded from empty prefs,
 * that store/load round-trips preserve values for all fields (radio selectors, nested-classes
 * checkbox, star-import package table, import-layout table), and that onChange is not fired
 * on load.
 */
class KotlinFormatterImportsPanelTest : NbTestCase("KotlinFormatterImportsPanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-imports-panel-${System.nanoTime()}")

    private fun settingsWithImports(block: KotlinCodeStyleSettings.() -> Unit): CodeStyleSettings {
        val src = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(src)
        src.getCustomSettings(KotlinCodeStyleSettings::class.java).block()
        return src
    }

    // ─── Defaults ─────────────────────────────────────────────────────────────

    /** Controls show KOTLIN_OFFICIAL defaults when the prefs node is empty. */
    fun testDefaults() {
        val panel = KotlinFormatterImportsPanel {}
        panel.load(freshPrefs())
        assertEquals(5, panel.getNameCountToUseStarImport())
        assertEquals(3, panel.getNameCountToUseStarImportForMembers())
        assertFalse(panel.isImportNestedClasses())
    }

    // ─── Radio button encoding ─────────────────────────────────────────────────

    /** NAME_COUNT_TO_USE_STAR_IMPORT = Int.MAX_VALUE → "use single name" radio selected. */
    fun testRadioSingleName() {
        val src = settingsWithImports { NAME_COUNT_TO_USE_STAR_IMPORT = Int.MAX_VALUE }
        val prefs = freshPrefs()
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)
        assertEquals(Int.MAX_VALUE, panel.getNameCountToUseStarImport())
    }

    /** NAME_COUNT_TO_USE_STAR_IMPORT = 1 → "use import with '*'" radio selected. */
    fun testRadioAlways() {
        val src = settingsWithImports { NAME_COUNT_TO_USE_STAR_IMPORT = 1 }
        val prefs = freshPrefs()
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)
        assertEquals(1, panel.getNameCountToUseStarImport())
    }

    /** NAME_COUNT_TO_USE_STAR_IMPORT = 7 → "when at least N" radio with spinner=7. */
    fun testRadioWhenAtLeast() {
        val src = settingsWithImports { NAME_COUNT_TO_USE_STAR_IMPORT = 7 }
        val prefs = freshPrefs()
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)
        assertEquals(7, panel.getNameCountToUseStarImport())
    }

    // ─── Basic round-trip ─────────────────────────────────────────────────────

    /** store() followed by load() on the same prefs preserves changed values. */
    fun testRoundTrip() {
        val prefs = freshPrefs()

        val src = settingsWithImports {
            NAME_COUNT_TO_USE_STAR_IMPORT = 2
            NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = 7
            IMPORT_NESTED_CLASSES = true
        }
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)
        assertEquals(2, panel.getNameCountToUseStarImport())
        assertEquals(7, panel.getNameCountToUseStarImportForMembers())
        assertTrue(panel.isImportNestedClasses())

        panel.setNameCountToUseStarImport(10)
        val out = freshPrefs()
        panel.store(out)

        val panel2 = KotlinFormatterImportsPanel {}
        panel2.load(out)
        assertEquals(10, panel2.getNameCountToUseStarImport())
        assertEquals(7, panel2.getNameCountToUseStarImportForMembers())
        assertTrue(panel2.isImportNestedClasses())
    }

    // ─── Star-imports table ────────────────────────────────────────────────────

    /**
     * PACKAGES_TO_USE_STAR_IMPORTS survives a store→load round-trip through the panel.
     */
    fun testStarImportsTableRoundTrip() {
        val prefs = freshPrefs()

        val src = settingsWithImports {
            PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable())
            PACKAGES_TO_USE_STAR_IMPORTS.addEntry(KotlinPackageEntry("com.example", false))
            PACKAGES_TO_USE_STAR_IMPORTS.addEntry(KotlinPackageEntry("org.test", true))
        }
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)

        val out = freshPrefs()
        panel.store(out)

        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(out, tmp)
        val entries = tmp.getCustomSettings(KotlinCodeStyleSettings::class.java)
            .PACKAGES_TO_USE_STAR_IMPORTS.getEntries()

        assertEquals(2, entries.size)
        assertEquals("com.example", entries[0].packageName)
        assertFalse(entries[0].withSubpackages)
        assertEquals("org.test", entries[1].packageName)
        assertTrue(entries[1].withSubpackages)
    }

    // ─── Import-layout table ──────────────────────────────────────────────────

    /**
     * PACKAGES_IMPORT_LAYOUT survives a store→load round-trip through the panel.
     */
    fun testImportLayoutTableRoundTrip() {
        val prefs = freshPrefs()

        val layoutTable = org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable()
        layoutTable.addEntry(KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY)
        layoutTable.addEntry(KotlinPackageEntry("java", true))

        val src = settingsWithImports {
            PACKAGES_IMPORT_LAYOUT.copyFrom(layoutTable)
        }
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)

        val out = freshPrefs()
        panel.store(out)

        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(out, tmp)
        val entries = tmp.getCustomSettings(KotlinCodeStyleSettings::class.java)
            .PACKAGES_IMPORT_LAYOUT.getEntries()

        assertEquals(2, entries.size)
        assertEquals(KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY, entries[0])
        assertEquals("java", entries[1].packageName)
        assertTrue(entries[1].withSubpackages)
    }

    // ─── Import aliases separately ─────────────────────────────────────────────

    /**
     * When PACKAGES_IMPORT_LAYOUT contains ALL_OTHER_ALIAS_IMPORTS_ENTRY, the
     * "Import aliases separately" checkbox must be selected after load().
     */
    fun testImportAliasesSeparatelyChecked() {
        val layoutTable = org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable()
        layoutTable.addEntry(KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY)
        layoutTable.addEntry(KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY)

        val src = settingsWithImports { PACKAGES_IMPORT_LAYOUT.copyFrom(layoutTable) }
        val prefs = freshPrefs()
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)

        val out = freshPrefs()
        panel.store(out)

        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(out, tmp)
        val stored = tmp.getCustomSettings(KotlinCodeStyleSettings::class.java)
            .PACKAGES_IMPORT_LAYOUT.getEntries()

        assertTrue("ALL_OTHER_ALIAS_IMPORTS_ENTRY should be preserved",
            stored.any { it == KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY })
    }

    /**
     * When PACKAGES_IMPORT_LAYOUT does not contain ALL_OTHER_ALIAS_IMPORTS_ENTRY,
     * the "Import aliases separately" checkbox must be unchecked after load().
     */
    fun testImportAliasesSeparatelyUnchecked() {
        val layoutTable = org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable()
        layoutTable.addEntry(KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY)

        val src = settingsWithImports { PACKAGES_IMPORT_LAYOUT.copyFrom(layoutTable) }
        val prefs = freshPrefs()
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)

        val out = freshPrefs()
        panel.store(out)

        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(out, tmp)
        val stored = tmp.getCustomSettings(KotlinCodeStyleSettings::class.java)
            .PACKAGES_IMPORT_LAYOUT.getEntries()

        assertFalse("ALL_OTHER_ALIAS_IMPORTS_ENTRY should be absent",
            stored.any { it == KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY })
    }

    // ─── onChange not fired on load ────────────────────────────────────────────

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinFormatterImportsPanel { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }
}
