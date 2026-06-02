// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.formatting.options

import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntry
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Tests for [KotlinCodeStylePreferences].
 *
 * <p>Verifies XML round-trip serialization via [KotlinCodeStyleSerializer] for
 * both [KotlinCodeStyleSettings] and [IndentOptions].
 */
class KotlinCodeStylePreferencesTest : NbTestCase("KotlinCodeStylePreferencesTest") {

    private val settings get() = KotlinFormatterUtils.getSettings()

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    override fun tearDown() {
        super.tearDown()
        // Reset to defaults so tests do not pollute each other.
        val ks = settings.kotlinCustomSettings
        ks.ALLOW_TRAILING_COMMA = false
        ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE = false
        ks.NAME_COUNT_TO_USE_STAR_IMPORT = KotlinCodeStyleSettings.DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT
        ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = KotlinCodeStyleSettings.DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS
        val opts = settings.getIndentOptions()
        opts.INDENT_SIZE = 4
        opts.CONTINUATION_INDENT_SIZE = 8
        opts.TAB_SIZE = 4
        opts.USE_TAB_CHARACTER = false
    }

    /** Returns an isolated in-memory preferences node for each test. */
    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-kotlin-code-style-${System.nanoTime()}")

    /** Verifies that boolean field changes survive a save/load round-trip. */
    fun testRoundTripBooleans() {
        val prefs = freshPrefs()
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA = true
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA_ON_CALL_SITE = true

        KotlinCodeStylePreferences.save(settings, prefs)
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA = false
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA_ON_CALL_SITE = false
        KotlinCodeStylePreferences.load(prefs, settings)

        assertTrue(settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA)
        assertTrue(settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA_ON_CALL_SITE)
    }

    /** Verifies that integer fields survive a save/load round-trip. */
    fun testRoundTripIntegers() {
        val prefs = freshPrefs()
        settings.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT = 7
        settings.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = 2

        KotlinCodeStylePreferences.save(settings, prefs)
        settings.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT = 5
        settings.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = 3
        KotlinCodeStylePreferences.load(prefs, settings)

        assertEquals(7, settings.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT)
        assertEquals(2, settings.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS)
    }

    /** Verifies that the package star-imports table survives a save/load round-trip. */
    fun testRoundTripPackageTable() {
        val prefs = freshPrefs()
        val ks = settings.kotlinCustomSettings
        ks.PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(
            org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable()
        )
        ks.PACKAGES_TO_USE_STAR_IMPORTS.addEntry(KotlinPackageEntry("com.example", true))
        ks.PACKAGES_TO_USE_STAR_IMPORTS.addEntry(KotlinPackageEntry("org.test", false))

        KotlinCodeStylePreferences.save(settings, prefs)
        ks.PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(
            org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable()
        )
        KotlinCodeStylePreferences.load(prefs, settings)

        assertEquals(2, ks.PACKAGES_TO_USE_STAR_IMPORTS.entryCount)
        assertEquals("com.example", ks.PACKAGES_TO_USE_STAR_IMPORTS.getEntryAt(0).packageName)
        assertTrue(ks.PACKAGES_TO_USE_STAR_IMPORTS.getEntryAt(0).withSubpackages)
        assertEquals("org.test", ks.PACKAGES_TO_USE_STAR_IMPORTS.getEntryAt(1).packageName)
        assertFalse(ks.PACKAGES_TO_USE_STAR_IMPORTS.getEntryAt(1).withSubpackages)
    }

    /** Verifies that the ALL_OTHER_IMPORTS sentinel survives a save/load round-trip. */
    fun testRoundTripAllOtherSentinel() {
        val prefs = freshPrefs()
        val ks = settings.kotlinCustomSettings
        ks.PACKAGES_IMPORT_LAYOUT.copyFrom(
            org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable()
        )
        ks.PACKAGES_IMPORT_LAYOUT.addEntry(KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY)
        ks.PACKAGES_IMPORT_LAYOUT.addEntry(KotlinPackageEntry("java", true))

        KotlinCodeStylePreferences.save(settings, prefs)
        ks.PACKAGES_IMPORT_LAYOUT.copyFrom(
            org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable()
        )
        KotlinCodeStylePreferences.load(prefs, settings)

        assertEquals(2, ks.PACKAGES_IMPORT_LAYOUT.entryCount)
        assertTrue(ks.PACKAGES_IMPORT_LAYOUT.getEntryAt(0).isSpecial)
        assertEquals("java", ks.PACKAGES_IMPORT_LAYOUT.getEntryAt(1).packageName)
    }

    /** Verifies that IndentOptions survive a save/load round-trip. */
    fun testRoundTripIndentOptions() {
        val prefs = freshPrefs()
        val opts = settings.getIndentOptions()
        opts.INDENT_SIZE = 2
        opts.CONTINUATION_INDENT_SIZE = 4
        opts.TAB_SIZE = 2
        opts.USE_TAB_CHARACTER = true

        KotlinCodeStylePreferences.save(settings, prefs)
        opts.INDENT_SIZE = 4
        opts.CONTINUATION_INDENT_SIZE = 8
        opts.TAB_SIZE = 4
        opts.USE_TAB_CHARACTER = false
        KotlinCodeStylePreferences.load(prefs, settings)

        assertEquals(2, opts.INDENT_SIZE)
        assertEquals(4, opts.CONTINUATION_INDENT_SIZE)
        assertEquals(2, opts.TAB_SIZE)
        assertTrue(opts.USE_TAB_CHARACTER)
    }

    /** Verifies that loading from an empty prefs node leaves settings unchanged. */
    fun testLoadFromEmptyPrefsKeepsCurrentValues() {
        val prefs = freshPrefs()
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA = true

        KotlinCodeStylePreferences.load(prefs, settings)

        assertTrue(settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA)
    }

    /** Verifies that malformed XML in prefs is handled without throwing. */
    fun testLoadMalformedXmlIsIgnored() {
        val prefs = freshPrefs()
        prefs.put(KotlinCodeStylePreferences.PREFS_KEY_KOTLIN, "not-valid-xml<<<")
        prefs.put(KotlinCodeStylePreferences.PREFS_KEY_INDENT, "also-invalid")

        // Must not throw; settings remain at their previous values.
        KotlinCodeStylePreferences.load(prefs, settings)
    }
}
