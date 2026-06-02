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
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Tests for [KotlinFormatterOtherPanel].
 *
 * <p>Uses the full IntelliJ environment so that [KotlinCodeStylePreferences]
 * serialization (via [io.github.nbplugins.kotlin.formatter.KotlinCodeStyleSerializer])
 * can create [CodeStyleSettings] instances correctly.
 */
class KotlinFormatterOtherPanelTest : NbTestCase("KotlinFormatterOtherPanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-other-panel-${System.nanoTime()}")

    /** Checkboxes are unchecked when prefs node is empty (matches KotlinCodeStyleSettings defaults). */
    fun testDefaultsAreFalse() {
        val panel = KotlinFormatterOtherPanel {}
        panel.load(freshPrefs())
        assertFalse(panel.isTrailingCommaDeclSelected())
        assertFalse(panel.isTrailingCommaCallSelected())
    }

    /** store() followed by a fresh load() on the same prefs preserves checkbox state. */
    fun testRoundTrip() {
        val prefs = freshPrefs()

        // Populate prefs with trailing comma = true via a settings object.
        val src = CodeStyleSettings()
        src.getCustomSettings(org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings::class.java)
            .ALLOW_TRAILING_COMMA = true
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterOtherPanel {}
        panel.load(prefs)
        assertTrue(panel.isTrailingCommaDeclSelected())

        // Flip the checkbox and store.
        panel.setTrailingCommaDeclSelected(false)
        val out = freshPrefs()
        panel.store(out)

        val panel2 = KotlinFormatterOtherPanel {}
        panel2.load(out)
        assertFalse(panel2.isTrailingCommaDeclSelected())
    }

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinFormatterOtherPanel { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }
}
