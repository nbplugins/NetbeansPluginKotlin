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
 * Tests for [KotlinFormatterIndentPanel].
 *
 * <p>Uses the full IntelliJ environment so that [KotlinCodeStylePreferences]
 * serialization (via [io.github.nbplugins.kotlin.formatter.KotlinCodeStyleSerializer])
 * can create [CodeStyleSettings] instances correctly.
 */
class KotlinFormatterIndentPanelTest : NbTestCase("KotlinFormatterIndentPanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-indent-panel-${System.nanoTime()}")

    /** Controls show IntelliJ defaults when the prefs node is empty. */
    fun testDefaults() {
        val panel = KotlinFormatterIndentPanel {}
        panel.load(freshPrefs())
        assertEquals(4, panel.getTabSize())
        assertEquals(4, panel.getIndentSize())
        assertEquals(8, panel.getContinuationIndentSize())
        assertFalse(panel.isUseTabCharacter())
    }

    /** store() followed by a fresh load() on the same prefs preserves all values. */
    fun testRoundTrip() {
        val prefs = freshPrefs()

        // Write non-default indent options.
        val src = CodeStyleSettings()
        val opts = src.indentOptions
        opts.TAB_SIZE = 2
        opts.INDENT_SIZE = 2
        opts.CONTINUATION_INDENT_SIZE = 4
        opts.USE_TAB_CHARACTER = true
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterIndentPanel {}
        panel.load(prefs)
        assertEquals(2, panel.getTabSize())
        assertEquals(2, panel.getIndentSize())
        assertEquals(4, panel.getContinuationIndentSize())
        assertTrue(panel.isUseTabCharacter())

        // Change via setter and store to a fresh prefs node.
        panel.setIndentSize(3)
        val out = freshPrefs()
        panel.store(out)

        val panel2 = KotlinFormatterIndentPanel {}
        panel2.load(out)
        assertEquals(3, panel2.getIndentSize())
        assertEquals(2, panel2.getTabSize())
        assertEquals(4, panel2.getContinuationIndentSize())
        assertTrue(panel2.isUseTabCharacter())
    }

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinFormatterIndentPanel { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }
}
