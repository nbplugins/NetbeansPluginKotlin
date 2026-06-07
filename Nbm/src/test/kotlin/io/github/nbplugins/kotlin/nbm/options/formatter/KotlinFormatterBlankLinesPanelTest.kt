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
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Tests for [KotlinFormatterBlankLinesPanel].
 *
 * <p>Uses the full IntelliJ environment so that [KotlinCodeStylePreferences]
 * serialization (via [io.github.nbplugins.kotlin.formatter.KotlinCodeStyleSerializer])
 * can create [CodeStyleSettings] instances correctly.
 */
class KotlinFormatterBlankLinesPanelTest : NbTestCase("KotlinFormatterBlankLinesPanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-blank-lines-panel-${System.nanoTime()}")

    /** All spinners show their defaults when the prefs node is empty. */
    fun testDefaults() {
        val panel = KotlinFormatterBlankLinesPanel {}
        panel.load(freshPrefs())
        // Keep maximum blank lines (CommonCodeStyleSettings defaults)
        assertEquals(2, panel.getKeepInDeclarations())
        assertEquals(2, panel.getKeepInCode())
        assertEquals(2, panel.getKeepBeforeRbrace())
        // Minimum blank lines (CommonCodeStyleSettings + KotlinCodeStyleSettings defaults)
        assertEquals(0, panel.getAfterClassHeader())
        assertEquals(0, panel.getBlankLinesWhenBranches())
        assertEquals(1, panel.getBlankLinesDeclWithAnnotation())
    }

    /** store() then load() on the same prefs preserves KotlinCodeStyleSettings fields. */
    fun testRoundTrip() {
        val prefs = freshPrefs()

        val src = CodeStyleSettings()
        val ks = src.kotlinCustomSettings
        ks.BLANK_LINES_AROUND_BLOCK_WHEN_BRANCHES = 2
        ks.BLANK_LINES_BEFORE_DECLARATION_WITH_COMMENT_OR_ANNOTATION_ON_SEPARATE_LINE = 3
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterBlankLinesPanel {}
        panel.load(prefs)
        assertEquals(2, panel.getBlankLinesWhenBranches())
        assertEquals(3, panel.getBlankLinesDeclWithAnnotation())

        panel.setBlankLinesWhenBranches(1)
        val out = freshPrefs()
        panel.store(out)

        val panel2 = KotlinFormatterBlankLinesPanel {}
        panel2.load(out)
        assertEquals(1, panel2.getBlankLinesWhenBranches())
        assertEquals(3, panel2.getBlankLinesDeclWithAnnotation())
    }

    /** store() then load() on the same prefs preserves CommonCodeStyleSettings fields. */
    fun testRoundTripCommonSettings() {
        val prefs = freshPrefs()

        val src = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(src)
        val cs = src.getCommonSettings(KotlinLanguage.INSTANCE)
        cs.KEEP_BLANK_LINES_IN_DECLARATIONS = 5
        cs.KEEP_BLANK_LINES_IN_CODE = 4
        cs.KEEP_BLANK_LINES_BEFORE_RBRACE = 3
        cs.BLANK_LINES_AFTER_CLASS_HEADER = 2
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterBlankLinesPanel {}
        panel.load(prefs)
        assertEquals(5, panel.getKeepInDeclarations())
        assertEquals(4, panel.getKeepInCode())
        assertEquals(3, panel.getKeepBeforeRbrace())
        assertEquals(2, panel.getAfterClassHeader())

        panel.setKeepInDeclarations(1)
        val out = freshPrefs()
        panel.store(out)

        val panel2 = KotlinFormatterBlankLinesPanel {}
        panel2.load(out)
        assertEquals(1, panel2.getKeepInDeclarations())
        // other fields survive the partial store
        assertEquals(4, panel2.getKeepInCode())
        assertEquals(3, panel2.getKeepBeforeRbrace())
        assertEquals(2, panel2.getAfterClassHeader())
    }

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinFormatterBlankLinesPanel { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }
}
