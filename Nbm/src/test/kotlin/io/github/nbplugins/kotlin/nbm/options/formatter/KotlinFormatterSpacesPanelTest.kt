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
 * Tests for [KotlinFormatterSpacesPanel].
 *
 * <p>Verifies that controls reflect IntelliJ defaults when loaded from empty prefs,
 * that store/load round-trips preserve values, and that onChange is not fired on load.
 */
class KotlinFormatterSpacesPanelTest : NbTestCase("KotlinFormatterSpacesPanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-spaces-panel-${System.nanoTime()}")

    /** Controls show KOTLIN_OFFICIAL defaults when the prefs node is empty. */
    fun testDefaults() {
        val panel = KotlinFormatterSpacesPanel {}
        panel.load(freshPrefs())
        // KOTLIN_OFFICIAL leaves SPACE_AROUND_ASSIGNMENT_OPERATORS at the CommonCodeStyleSettings default (true)
        assertTrue(panel.isSpaceAroundAssignmentOperators())
        // SPACE_AROUND_RANGE default in KotlinCodeStyleSettings is false
        assertFalse(panel.isSpaceAroundRange())
    }

    /** store() followed by load() on the same prefs preserves changed values. */
    fun testRoundTrip() {
        val prefs = freshPrefs()

        // Seed prefs with non-default values.
        val src = CodeStyleSettings()
        io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils.registerKotlinProvider(src)
        val cs = src.getCommonSettings(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE)
        cs.SPACE_AROUND_ASSIGNMENT_OPERATORS = false
        cs.SPACE_BEFORE_IF_PARENTHESES = false
        src.getCustomSettings(org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings::class.java).SPACE_AROUND_RANGE = true
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterSpacesPanel {}
        panel.load(prefs)
        assertFalse(panel.isSpaceAroundAssignmentOperators())
        assertFalse(panel.isSpaceBeforeIfParentheses())
        assertTrue(panel.isSpaceAroundRange())

        // Change via panel and store.
        panel.setSpaceAroundAssignmentOperators(true)
        val out = freshPrefs()
        panel.store(out)

        val panel2 = KotlinFormatterSpacesPanel {}
        panel2.load(out)
        assertTrue(panel2.isSpaceAroundAssignmentOperators())
        assertFalse(panel2.isSpaceBeforeIfParentheses())
        assertTrue(panel2.isSpaceAroundRange())
    }

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinFormatterSpacesPanel { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }
}
