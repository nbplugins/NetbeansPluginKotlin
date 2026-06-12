/*******************************************************************************
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
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import org.netbeans.api.editor.settings.SimpleValueNames
import utils.KotlinTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.prefs.PreferenceChangeEvent
import java.util.prefs.PreferenceChangeListener

/**
 * Unit tests for [KotlinCodeStylePreferencesProvider].
 *
 * <p>Each test mutates the global [com.intellij.psi.codeStyle.CodeStyleSettings]
 * singleton or the [ProjectCodeStyleStorage] cache and asserts that the
 * resulting [java.util.prefs.Preferences] view exposed to NetBeans reflects
 * the change.
 */
class KotlinCodeStylePreferencesProviderTest
    : KotlinTestCase("KotlinCodeStylePreferencesProviderTest", "formatting") {

    private val provider = KotlinCodeStylePreferencesProvider()

    override fun tearDown() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        // Restore reasonable global defaults so tests don't leak state.
        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 4
        KotlinFormatterUtils.getSettings().indentOptions.TAB_SIZE = 4
        KotlinFormatterUtils.getSettings().indentOptions.USE_TAB_CHARACTER = false
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        super.tearDown()
    }

    private fun perProjectSettings(
        indentSize: Int = 4,
        tabSize: Int = 4,
        useTab: Boolean = false
    ): CodeStyleSettings {
        val s = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(s)
        s.indentOptions.INDENT_SIZE = indentSize
        s.indentOptions.TAB_SIZE = tabSize
        s.indentOptions.USE_TAB_CHARACTER = useTab
        return s
    }

    /** Global INDENT_SIZE is exposed as `indent-shift-width` for files with no project owner. */
    fun testForFileGlobalReturnsIndentShiftWidth() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 2
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        val prefs = KotlinCodeStylePreferencesProvider.preferencesFor(null)
        assertEquals(2, prefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))
    }

    /** A per-project override takes precedence over the global INDENT_SIZE. */
    fun testForFileProjectOverrideTakesPrecedence() {
        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 2
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, perProjectSettings(indentSize = 8))
        KotlinCodeStylePreferencesProvider.notifyChanged(project)
        val prefs = KotlinCodeStylePreferencesProvider.preferencesFor(project)
        assertEquals(8, prefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))
        // And the project-less (global) view stays at 2.
        val globalPrefs = KotlinCodeStylePreferencesProvider.preferencesFor(null)
        assertEquals(2, globalPrefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))
    }

    /** Without a per-project override, the project view falls back to the global INDENT_SIZE. */
    fun testForFileProjectWithoutOverrideFallsBackToGlobal() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 6
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        val prefs = KotlinCodeStylePreferencesProvider.preferencesFor(project)
        assertEquals(6, prefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))
    }

    /** `expand-tabs` mirrors `!USE_TAB_CHARACTER`. */
    fun testExpandTabsReflectsUseTabCharacter() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        KotlinFormatterUtils.getSettings().indentOptions.USE_TAB_CHARACTER = true
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        var prefs = KotlinCodeStylePreferencesProvider.preferencesFor(null)
        assertFalse(prefs.getBoolean(SimpleValueNames.EXPAND_TABS, true))

        KotlinFormatterUtils.getSettings().indentOptions.USE_TAB_CHARACTER = false
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        prefs = KotlinCodeStylePreferencesProvider.preferencesFor(null)
        assertTrue(prefs.getBoolean(SimpleValueNames.EXPAND_TABS, false))
    }

    /** `tab-size` and `spaces-per-tab` both reflect TAB_SIZE. */
    fun testTabSizeReturnsTabSize() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        KotlinFormatterUtils.getSettings().indentOptions.TAB_SIZE = 3
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        val prefs = KotlinCodeStylePreferencesProvider.preferencesFor(null)
        assertEquals(3, prefs.getInt(SimpleValueNames.TAB_SIZE, -1))
        assertEquals(3, prefs.getInt(SimpleValueNames.SPACES_PER_TAB, -1))
    }

    /** notifyChanged fires PreferenceChangeEvent for changed keys. */
    fun testNotifyChangedFiresPreferenceChangeEvent() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 2
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        val prefs = KotlinCodeStylePreferencesProvider.preferencesFor(null)

        val latch = CountDownLatch(1)
        val received = mutableListOf<PreferenceChangeEvent>()
        val listener = PreferenceChangeListener { e ->
            received.add(e)
            if (e.key == SimpleValueNames.INDENT_SHIFT_WIDTH) latch.countDown()
        }
        prefs.addPreferenceChangeListener(listener)
        try {
            KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 4
            KotlinCodeStylePreferencesProvider.notifyChanged(null)
            // AbstractPreferences fires events asynchronously on a daemon
            // event-dispatch thread. Allow generous time for delivery in CI.
            assertTrue(
                "expected PreferenceChangeEvent for indent-shift-width within 10s",
                latch.await(10, TimeUnit.SECONDS)
            )
        } finally {
            prefs.removePreferenceChangeListener(listener)
        }

        val indentEvent = received.first { it.key == SimpleValueNames.INDENT_SHIFT_WIDTH }
        assertEquals("4", indentEvent.newValue)
        assertEquals(4, prefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))
    }

    /** notifyChanged(null) cascades the refresh to every cached project view. */
    fun testNotifyChangedCascadesToInheritingProjects() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 2
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        val projectPrefs = KotlinCodeStylePreferencesProvider.preferencesFor(project)
        assertEquals(2, projectPrefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))

        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 5
        KotlinCodeStylePreferencesProvider.notifyChanged(null)
        assertEquals(5, projectPrefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))
    }

    /** forFile resolves the owning project and routes to the per-project view. */
    fun testForFileResolvesProjectOwner() {
        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 2
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, perProjectSettings(indentSize = 7))
        KotlinCodeStylePreferencesProvider.notifyChanged(project)
        val fo = dir.children.firstOrNull { it.ext == "kt" } ?: dir
        val prefs = provider.forFile(fo, "text/x-kotlin")
        assertNotNull(prefs)
        assertEquals(7, prefs!!.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))
    }

    /**
     * Reproduces the bug where vertical indent-guide lines stayed on the global
     * indent after a project with `.idea/codeStyles/Project.xml` was opened:
     * if NetBeans queried [KotlinCodeStylePreferencesProvider.preferencesFor]
     * *before* the project-opened hook populated the storage cache, the
     * resulting [java.util.prefs.Preferences] instance captured the global
     * value and was never refreshed when the hook ran later.
     *
     * [ProjectCodeStyleStorage.onProjectOpened] must call
     * [KotlinCodeStylePreferencesProvider.notifyChanged] so the cached
     * preferences view is refreshed in place.
     */
    fun testOnProjectOpenedRefreshesCachedProviderPreferences() {
        // Global indent = 2.
        KotlinFormatterUtils.getSettings().indentOptions.INDENT_SIZE = 2
        KotlinCodeStylePreferencesProvider.notifyChanged(null)

        // Pre-create the provider's per-project preferences — captures global=2,
        // mirroring NetBeans's first-paint query before the project hook runs.
        val prefs = KotlinCodeStylePreferencesProvider.preferencesFor(project)
        assertEquals(2, prefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1))

        // Persist a per-project override (indent=4) to .idea/codeStyles/Project.xml
        // and then close to drop the storage cache entry — the on-disk file stays.
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, perProjectSettings(indentSize = 4))
        ProjectCodeStyleStorage.onProjectClosed(project)

        // Simulate the project-opened hook. The fix wires this to notifyChanged,
        // which refreshes the already-cached preferences instance.
        try {
            ProjectCodeStyleStorage.onProjectOpened(project)
            assertEquals(
                "preferences view should reflect per-project indent after onProjectOpened",
                4, prefs.getInt(SimpleValueNames.INDENT_SHIFT_WIDTH, -1)
            )
        } finally {
            // Remove the .idea/codeStyles files so the workspace is clean.
            ProjectCodeStyleStorage.onProjectSettingsCleared(project)
        }
    }

    /** forFile returns null for non-Kotlin MIME types so other languages are unaffected. */
    fun testForFileReturnsNullForNonKotlinMime() {
        val fo = dir.children.firstOrNull() ?: dir
        assertNull(provider.forFile(fo, "text/x-java"))
        assertNull(provider.forFile(fo, "text/plain"))
    }
}
