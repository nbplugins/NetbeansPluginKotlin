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

import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettings
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Tests for [KotlinFormatterImportsPanel].
 *
 * <p>Verifies that controls reflect KOTLIN_OFFICIAL defaults when loaded from empty prefs,
 * that store/load round-trips preserve values, and that onChange is not fired on load.
 */
class KotlinFormatterImportsPanelTest : NbTestCase("KotlinFormatterImportsPanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-imports-panel-${System.nanoTime()}")

    /** Controls show KOTLIN_OFFICIAL defaults when the prefs node is empty. */
    fun testDefaults() {
        val panel = KotlinFormatterImportsPanel {}
        panel.load(freshPrefs())
        // KOTLIN_OFFICIAL: NAME_COUNT_TO_USE_STAR_IMPORT = 5, NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = 3
        assertEquals(5, panel.getNameCountToUseStarImport())
        assertEquals(3, panel.getNameCountToUseStarImportForMembers())
        assertFalse(panel.isImportNestedClasses())
    }

    /** store() followed by load() on the same prefs preserves changed values. */
    fun testRoundTrip() {
        val prefs = freshPrefs()

        val src = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(src)
        val ks = src.getCustomSettings(KotlinCodeStyleSettings::class.java)
        ks.NAME_COUNT_TO_USE_STAR_IMPORT = 2
        ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = 7
        ks.IMPORT_NESTED_CLASSES = true
        KotlinCodeStylePreferences.save(src, prefs)

        val panel = KotlinFormatterImportsPanel {}
        panel.load(prefs)
        assertEquals(2, panel.getNameCountToUseStarImport())
        assertEquals(7, panel.getNameCountToUseStarImportForMembers())
        assertTrue(panel.isImportNestedClasses())

        panel.setNameCountToUseStarImport(10)
        val out = freshPrefs()
        panel.store(out)

        val panel2 = KotlinFormatterImportsPanel {}
        panel2.load(out)
        assertEquals(10, panel2.getNameCountToUseStarImport())
        assertEquals(7, panel2.getNameCountToUseStarImportForMembers())
        assertTrue(panel2.isImportNestedClasses())
    }

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinFormatterImportsPanel { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }
}
