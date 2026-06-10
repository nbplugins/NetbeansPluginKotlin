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
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Verifies that [KotlinCodeStylePreferences.loadIntoGlobal] — the call wired
 * into `KotlinInstaller.restored()` — populates the global formatter singleton
 * with persisted indent and common-settings values, so the first format after
 * restart uses saved settings rather than the in-memory defaults.
 */
class StartupLoadIntoGlobalTest : NbTestCase("StartupLoadIntoGlobalTest") {

    private val settings get() = KotlinFormatterUtils.getSettings()

    private var savedIndent = 0
    private var savedTabSize = 0
    private var savedUseTab = false
    private var savedSpaceAroundAssignment = true

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
        // Snapshot the singleton state so other tests in the same JVM run are not affected.
        val opts = settings.indentOptions
        savedIndent = opts.INDENT_SIZE
        savedTabSize = opts.TAB_SIZE
        savedUseTab = opts.USE_TAB_CHARACTER
        savedSpaceAroundAssignment =
            settings.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS
    }

    override fun tearDown() {
        val opts = settings.indentOptions
        opts.INDENT_SIZE = savedIndent
        opts.TAB_SIZE = savedTabSize
        opts.USE_TAB_CHARACTER = savedUseTab
        settings.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS =
            savedSpaceAroundAssignment
        super.tearDown()
    }

    /** Returns an isolated in-memory preferences node for each test. */
    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-startup-load-${System.nanoTime()}")

    /** [loadIntoGlobal] copies indent and common-settings values from prefs into the singleton. */
    fun testLoadIntoGlobalPopulatesSingleton() {
        val prefs = freshPrefs()
        // Author a snapshot in a separate settings instance and save it to prefs.
        val source = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(source)
        source.indentOptions.INDENT_SIZE = 2
        source.indentOptions.TAB_SIZE = 2
        source.indentOptions.USE_TAB_CHARACTER = true
        source.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS = false
        KotlinCodeStylePreferences.save(source, prefs)

        // Pretend the singleton has whatever the last test or default state put there.
        settings.indentOptions.INDENT_SIZE = 4
        settings.indentOptions.TAB_SIZE = 4
        settings.indentOptions.USE_TAB_CHARACTER = false
        settings.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS = true

        KotlinCodeStylePreferences.loadIntoGlobal(prefs)

        assertEquals("INDENT_SIZE should match persisted value", 2, settings.indentOptions.INDENT_SIZE)
        assertEquals("TAB_SIZE should match persisted value", 2, settings.indentOptions.TAB_SIZE)
        assertTrue("USE_TAB_CHARACTER should match persisted value", settings.indentOptions.USE_TAB_CHARACTER)
        assertFalse(
            "SPACE_AROUND_ASSIGNMENT_OPERATORS should match persisted value",
            settings.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS
        )
    }
}
