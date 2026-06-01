// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.formatting.options

import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.netbeans.junit.NbTestCase
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntry
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable
import java.util.prefs.Preferences

/**
 * Tests for [KotlinCodeStylePreferences].
 *
 * <p>Uses an in-process [Preferences] node and the global KotlinFormatterUtils
 * settings singleton initialised by the IntelliJ application environment.
 */
class KotlinCodeStylePreferencesTest : NbTestCase("KotlinCodeStylePreferencesTest") {

    private val settings get() = KotlinFormatterUtils.getSettings()

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    /** Returns an isolated in-memory preferences node for each test. */
    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-kotlin-code-style-${System.nanoTime()}")

    override fun tearDown() {
        super.tearDown()
        // Reset global settings to defaults so tests do not pollute each other.
        val ks = settings.kotlinCustomSettings
        ks.ALLOW_TRAILING_COMMA = false
        ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE = false
        ks.NAME_COUNT_TO_USE_STAR_IMPORT = KotlinCodeStyleSettings.DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT
        ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = KotlinCodeStyleSettings.DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS
        ks.IMPORT_NESTED_CLASSES = false
    }

    /** Verifies that [KotlinCodeStylePreferences.save] writes boolean fields to prefs. */
    fun testSaveBooleans() {
        val prefs = freshPrefs()
        val ks = settings.kotlinCustomSettings
        ks.ALLOW_TRAILING_COMMA = true
        ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE = false

        KotlinCodeStylePreferences.save(settings, prefs)

        assertTrue(prefs.getBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA, false))
        assertFalse(prefs.getBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA_ON_CALL_SITE, true))
    }

    /** Verifies that [KotlinCodeStylePreferences.load] reads boolean fields from prefs. */
    fun testLoadBooleans() {
        val prefs = freshPrefs()
        prefs.putBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA, true)
        prefs.putBoolean(KotlinCodeStylePreferences.KEY_ALLOW_TRAILING_COMMA_ON_CALL_SITE, true)

        KotlinCodeStylePreferences.load(prefs, settings)

        val ks = settings.kotlinCustomSettings
        assertTrue(ks.ALLOW_TRAILING_COMMA)
        assertTrue(ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE)
    }

    /** Verifies that [KotlinCodeStylePreferences.save] writes integer threshold fields. */
    fun testSaveIntegers() {
        val prefs = freshPrefs()
        val ks = settings.kotlinCustomSettings
        ks.NAME_COUNT_TO_USE_STAR_IMPORT = 7
        ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = 2

        KotlinCodeStylePreferences.save(settings, prefs)

        assertEquals(7, prefs.getInt(KotlinCodeStylePreferences.KEY_NAME_COUNT_TO_USE_STAR_IMPORT, 0))
        assertEquals(2, prefs.getInt(KotlinCodeStylePreferences.KEY_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS, 0))
    }

    /** Verifies that [KotlinCodeStylePreferences.load] reads integer threshold fields. */
    fun testLoadIntegers() {
        val prefs = freshPrefs()
        prefs.putInt(KotlinCodeStylePreferences.KEY_NAME_COUNT_TO_USE_STAR_IMPORT, 10)
        prefs.putInt(KotlinCodeStylePreferences.KEY_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS, 4)

        KotlinCodeStylePreferences.load(prefs, settings)

        val ks = settings.kotlinCustomSettings
        assertEquals(10, ks.NAME_COUNT_TO_USE_STAR_IMPORT)
        assertEquals(4, ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS)
    }

    /** Verifies a round-trip save/load preserves non-special package entries. */
    fun testRoundTripPackageTable() {
        val prefs = freshPrefs()
        val ks = settings.kotlinCustomSettings
        ks.PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(KotlinPackageEntryTable())
        ks.PACKAGES_TO_USE_STAR_IMPORTS.addEntry(KotlinPackageEntry("com.example", true))
        ks.PACKAGES_TO_USE_STAR_IMPORTS.addEntry(KotlinPackageEntry("org.test", false))

        KotlinCodeStylePreferences.save(settings, prefs)
        ks.PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(KotlinPackageEntryTable())
        KotlinCodeStylePreferences.load(prefs, settings)

        assertEquals(2, ks.PACKAGES_TO_USE_STAR_IMPORTS.entryCount)
        assertEquals("com.example", ks.PACKAGES_TO_USE_STAR_IMPORTS.getEntryAt(0).packageName)
        assertTrue(ks.PACKAGES_TO_USE_STAR_IMPORTS.getEntryAt(0).withSubpackages)
        assertEquals("org.test", ks.PACKAGES_TO_USE_STAR_IMPORTS.getEntryAt(1).packageName)
        assertFalse(ks.PACKAGES_TO_USE_STAR_IMPORTS.getEntryAt(1).withSubpackages)
    }

    /** Verifies a round-trip save/load preserves the ALL_OTHER_IMPORTS_ENTRY sentinel. */
    fun testRoundTripAllOtherSentinel() {
        val prefs = freshPrefs()
        val ks = settings.kotlinCustomSettings
        ks.PACKAGES_IMPORT_LAYOUT.copyFrom(KotlinPackageEntryTable())
        ks.PACKAGES_IMPORT_LAYOUT.addEntry(KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY)
        ks.PACKAGES_IMPORT_LAYOUT.addEntry(KotlinPackageEntry("java", true))

        KotlinCodeStylePreferences.save(settings, prefs)
        ks.PACKAGES_IMPORT_LAYOUT.copyFrom(KotlinPackageEntryTable())
        KotlinCodeStylePreferences.load(prefs, settings)

        assertEquals(2, ks.PACKAGES_IMPORT_LAYOUT.entryCount)
        assertTrue(ks.PACKAGES_IMPORT_LAYOUT.getEntryAt(0).isSpecial)
        assertEquals("java", ks.PACKAGES_IMPORT_LAYOUT.getEntryAt(1).packageName)
    }

    /** Verifies [KotlinCodeStylePreferences.load] uses field defaults when prefs node is empty. */
    fun testLoadFromEmptyPrefsUsesDefaults() {
        val prefs = freshPrefs()
        val ks = settings.kotlinCustomSettings
        ks.ALLOW_TRAILING_COMMA = true

        KotlinCodeStylePreferences.load(prefs, settings)

        // When prefs is empty the existing field value (true) is kept as the fallback.
        assertTrue(ks.ALLOW_TRAILING_COMMA)
    }
}
