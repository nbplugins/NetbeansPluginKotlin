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
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Tests for [KotlinFormatterCodeGenPanel].
 *
 * <p>Verifies the load/store round-trip for commenter settings and
 * the enablement dependency between "Add a space" and "Enforce on reformat".
 */
class KotlinFormatterCodeGenPanelTest : NbTestCase("KotlinFormatterCodeGenPanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-codegen-panel-${System.nanoTime()}")

    private fun readCommon(prefs: Preferences): CommonCodeStyleSettings {
        val css = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(css)
        KotlinCodeStylePreferences.load(prefs, css)
        return css.getCommonSettings(KotlinLanguage.INSTANCE)
    }

    private fun writeCommon(prefs: Preferences, block: CommonCodeStyleSettings.() -> Unit) {
        val css = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(css)
        css.getCommonSettings(KotlinLanguage.INSTANCE).block()
        KotlinCodeStylePreferences.save(css, prefs)
    }

    /** All five settings survive a load → store round-trip when set to true. */
    fun testAllTrueRoundTrip() {
        val prefs = freshPrefs()
        writeCommon(prefs) {
            LINE_COMMENT_AT_FIRST_COLUMN      = true
            LINE_COMMENT_ADD_SPACE            = true
            LINE_COMMENT_ADD_SPACE_ON_REFORMAT = true
            BLOCK_COMMENT_AT_FIRST_COLUMN     = true
            BLOCK_COMMENT_ADD_SPACE           = true
        }

        val panel = KotlinFormatterCodeGenPanel {}
        panel.load(prefs)
        val out = freshPrefs()
        panel.store(out)

        val common = readCommon(out)
        assertTrue(common.LINE_COMMENT_AT_FIRST_COLUMN)
        assertTrue(common.LINE_COMMENT_ADD_SPACE)
        assertTrue(common.LINE_COMMENT_ADD_SPACE_ON_REFORMAT)
        assertTrue(common.BLOCK_COMMENT_AT_FIRST_COLUMN)
        assertTrue(common.BLOCK_COMMENT_ADD_SPACE)
    }

    /** All five settings survive a load → store round-trip when set to false. */
    fun testAllFalseRoundTrip() {
        val prefs = freshPrefs()
        writeCommon(prefs) {
            LINE_COMMENT_AT_FIRST_COLUMN      = false
            LINE_COMMENT_ADD_SPACE            = false
            LINE_COMMENT_ADD_SPACE_ON_REFORMAT = false
            BLOCK_COMMENT_AT_FIRST_COLUMN     = false
            BLOCK_COMMENT_ADD_SPACE           = false
        }

        val panel = KotlinFormatterCodeGenPanel {}
        panel.load(prefs)
        val out = freshPrefs()
        panel.store(out)

        val common = readCommon(out)
        assertFalse(common.LINE_COMMENT_AT_FIRST_COLUMN)
        assertFalse(common.LINE_COMMENT_ADD_SPACE)
        assertFalse(common.LINE_COMMENT_ADD_SPACE_ON_REFORMAT)
        assertFalse(common.BLOCK_COMMENT_AT_FIRST_COLUMN)
        assertFalse(common.BLOCK_COMMENT_ADD_SPACE)
    }

    /** "Enforce on reformat" is disabled when "Add a space" is unchecked after load. */
    fun testEnforceDisabledWhenAddSpaceUnchecked() {
        val prefs = freshPrefs()
        writeCommon(prefs) { LINE_COMMENT_ADD_SPACE = false }

        val panel = KotlinFormatterCodeGenPanel {}
        panel.load(prefs)

        assertFalse(panel.cbLineEnforce.isEnabled)
    }

    /** "Enforce on reformat" is enabled when "Add a space" is checked after load. */
    fun testEnforceEnabledWhenAddSpaceChecked() {
        val prefs = freshPrefs()
        writeCommon(prefs) { LINE_COMMENT_ADD_SPACE = true }

        val panel = KotlinFormatterCodeGenPanel {}
        panel.load(prefs)

        assertTrue(panel.cbLineEnforce.isEnabled)
    }

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinFormatterCodeGenPanel { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }
}
