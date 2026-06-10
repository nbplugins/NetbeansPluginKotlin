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
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Tests for [KotlinFormatterOtherPanel].
 *
 * <p>Verifies the load/store round-trip for trailing-comma settings.
 * The panel has no public accessors for checkbox state; correctness is
 * validated by storing to a prefs node and reading back the settings.
 */
class KotlinFormatterOtherPanelTest : NbTestCase("KotlinFormatterOtherPanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-other-panel-${System.nanoTime()}")

    private fun readSettings(prefs: Preferences): KotlinCodeStyleSettings {
        val css = CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, css)
        return css.getCustomSettings(KotlinCodeStyleSettings::class.java)
    }

    private fun writeSettings(prefs: Preferences, block: KotlinCodeStyleSettings.() -> Unit) {
        val css = CodeStyleSettings()
        css.getCustomSettings(KotlinCodeStyleSettings::class.java).block()
        KotlinCodeStylePreferences.save(css, prefs)
    }

    /** Both settings are false when the prefs node is empty (raw KotlinCodeStyleSettings defaults). */
    fun testDefaultsAreFalse() {
        val panel = KotlinFormatterOtherPanel {}
        panel.load(freshPrefs())
        val out = freshPrefs()
        panel.store(out)
        val ks = readSettings(out)
        assertFalse(ks.ALLOW_TRAILING_COMMA)
        assertFalse(ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE)
    }

    /** ALLOW_TRAILING_COMMA=true is preserved through a load→store round-trip. */
    fun testDeclRoundTrip() {
        val prefs = freshPrefs()
        writeSettings(prefs) { ALLOW_TRAILING_COMMA = true }

        val panel = KotlinFormatterOtherPanel {}
        panel.load(prefs)
        val out = freshPrefs()
        panel.store(out)

        assertTrue(readSettings(out).ALLOW_TRAILING_COMMA)
    }

    /** Both settings=true are preserved when master is on. */
    fun testCallSiteRoundTrip() {
        val prefs = freshPrefs()
        writeSettings(prefs) {
            ALLOW_TRAILING_COMMA = true
            ALLOW_TRAILING_COMMA_ON_CALL_SITE = true
        }

        val panel = KotlinFormatterOtherPanel {}
        panel.load(prefs)
        val out = freshPrefs()
        panel.store(out)

        val ks = readSettings(out)
        assertTrue(ks.ALLOW_TRAILING_COMMA)
        assertTrue(ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE)
    }

    /** When master (ALLOW_TRAILING_COMMA) is false, call-site is stored as false regardless of its loaded value. */
    fun testCallSiteForcedFalseWhenMasterOff() {
        val prefs = freshPrefs()
        writeSettings(prefs) {
            ALLOW_TRAILING_COMMA = false
            ALLOW_TRAILING_COMMA_ON_CALL_SITE = true   // unusual state: call-site on, master off
        }

        val panel = KotlinFormatterOtherPanel {}
        panel.load(prefs)
        val out = freshPrefs()
        panel.store(out)

        val ks = readSettings(out)
        assertFalse(ks.ALLOW_TRAILING_COMMA)
        assertFalse(ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE)
    }

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinFormatterOtherPanel { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }
}
