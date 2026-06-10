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
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.netbeans.junit.NbTestCase
import java.util.prefs.Preferences

/**
 * Tests for [KotlinSettingsTreePanel].
 *
 * <p>Verifies that the panel builds without error, that [KotlinSettingsTreePanel.load]
 * does not fire onChange, and that store/load round-trips preserve modified values.
 */
class KotlinSettingsTreePanelTest : NbTestCase("KotlinSettingsTreePanelTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    private fun freshPrefs(): Preferences =
        Preferences.userRoot().node("test-settings-tree-${System.nanoTime()}")

    /** Panel for SPACING_SETTINGS builds without error and shows at least one group. */
    fun testSpacingPanelBuilds() {
        val panel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS) {}
        panel.load(freshPrefs())
        assertTrue("Spacing panel must show at least one group", panel.treePanel.tree.rowCount > 0)
    }

    /** Panel for WRAPPING_AND_BRACES_SETTINGS builds without error and shows at least one group. */
    fun testWrappingPanelBuilds() {
        val panel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS) {}
        panel.load(freshPrefs())
        assertTrue("Wrapping panel must show at least one group", panel.treePanel.tree.rowCount > 0)
    }

    /**
     * Wrapping tree prepends the three global wrap controls as the first root-level leaves:
     * Hard wrap at, Wrap on typing, Visual guides.
     */
    fun testWrappingPanelHasHardWrapControls() {
        val panel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS) {}
        panel.load(freshPrefs())
        val root = panel.treePanel.tree.model.root as javax.swing.tree.DefaultMutableTreeNode
        val firstThree = (0 until 3)
            .map { (root.getChildAt(it) as javax.swing.tree.DefaultMutableTreeNode).userObject }
        assertTrue("First root node must be IntFieldItem 'Hard wrap at'",
            firstThree[0] is IntFieldItem && (firstThree[0] as IntFieldItem).label == "Hard wrap at")
        assertTrue("Second root node must be WrapItem 'Wrap on typing'",
            firstThree[1] is WrapItem && (firstThree[1] as WrapItem).label == "Wrap on typing")
        assertTrue("Third root node must be TextFieldItem 'Visual guides'",
            firstThree[2] is TextFieldItem && (firstThree[2] as TextFieldItem).label == "Visual guides")
    }

    /**
     * Wrapping group labels use "Function …" naming (not "Method …"), and the order matches
     * IDEA: 'if()' statement → do…while → try → … → Assignment statement → Enum constants →
     * Annotations at the tail.
     */
    fun testWrappingGroupOrder() {
        val panel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS) {}
        panel.load(freshPrefs())
        val root = panel.treePanel.tree.model.root as javax.swing.tree.DefaultMutableTreeNode
        val groupLabels = (0 until root.childCount)
            .map { (root.getChildAt(it) as javax.swing.tree.DefaultMutableTreeNode).userObject.toString() }
            .filter { it.isNotEmpty() }
        assertTrue("No 'Method …' group survives renaming: $groupLabels",
            groupLabels.none { it.startsWith("Method ") })
        assertTrue("Legacy 'Control flow' group must be gone: $groupLabels",
            !groupLabels.contains("Control flow"))

        fun idx(label: String) = groupLabels.indexOf(label)
        val funcCall = idx("Function call arguments")
        val funcParens = idx("Function parentheses")
        val chained = idx("Chained function calls")
        val ifStmt = idx("'if()' statement")
        val doWhile = idx("do...while() statement")
        val tryStmt = idx("try statement(s)")
        val whenStmt = idx("'when' statements")
        val assign = idx("Assignment statement")
        val enum = idx("Enum constants")
        val annot = idx("Annotations")

        assertTrue("'Function call arguments' before 'Function parentheses'", funcCall in 0..<funcParens)
        assertTrue("'if()' statement directly after Chained function calls",
            chained >= 0 && ifStmt == chained + 1)
        assertTrue("do...while() after 'if()' statement", doWhile in (ifStmt + 1)..Int.MAX_VALUE)
        assertTrue("try statement(s) after do...while()", tryStmt in (doWhile + 1)..Int.MAX_VALUE)
        assertTrue("'when' statements present", whenStmt >= 0)
        assertTrue("Assignment statement → Enum constants → Annotations order",
            assign >= 0 && enum > assign && annot > enum)
    }

    /** "Function parentheses" group contains the leaf "Align when multiline" (not the legacy label). */
    fun testFunctionParenthesesLeafLabel() {
        val panel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS) {}
        panel.load(freshPrefs())
        val root = panel.treePanel.tree.model.root as javax.swing.tree.DefaultMutableTreeNode
        val parens = (0 until root.childCount)
            .map { root.getChildAt(it) as javax.swing.tree.DefaultMutableTreeNode }
            .firstOrNull { it.userObject.toString() == "Function parentheses" }
        assertNotNull("'Function parentheses' group must be present", parens)
        val leafLabels = (0 until parens!!.childCount).map {
            val obj = (parens.getChildAt(it) as javax.swing.tree.DefaultMutableTreeNode).userObject
            (obj as? OptionItem)?.label ?: obj.toString()
        }
        assertTrue("Leaf 'Align when multiline' must be present in $leafLabels",
            leafLabels.contains("Align when multiline"))
        assertFalse("Legacy 'Align brackets in method parentheses' leaf must not appear",
            leafLabels.any { it.startsWith("Align brackets") })
    }

    /** onChange callback is not invoked during load(). */
    fun testOnChangeNotCalledOnLoad() {
        var callCount = 0
        val panel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS) { callCount++ }
        panel.load(freshPrefs())
        assertEquals(0, callCount)
    }

    /**
     * store() followed by load() on the same prefs node preserves a value changed
     * directly via the live settings object (simulating a checkbox toggle).
     */
    fun testSpacingRoundTrip() {
        val prefs = freshPrefs()

        val panel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS) {}
        panel.load(prefs)

        // Read current value (IntelliJ default for SPACE_AROUND_ASSIGNMENT_OPERATORS is true).
        val cs = panel.settings.getCommonSettings(KotlinLanguage.INSTANCE)
        assertTrue(cs.SPACE_AROUND_ASSIGNMENT_OPERATORS)

        // Flip to false and store.
        cs.SPACE_AROUND_ASSIGNMENT_OPERATORS = false
        panel.store(prefs)

        // Load into a fresh panel from the same prefs and verify the value was preserved.
        val verify = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(verify)
        KotlinCodeStylePreferences.load(prefs, verify)
        assertFalse(verify.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS)
    }

    /** RIGHT_MARGIN persists through save → load cycle. */
    fun testRightMarginRoundTrip() {
        val prefs = freshPrefs()
        val src = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(src)
        src.getCommonSettings(KotlinLanguage.INSTANCE).RIGHT_MARGIN = 80
        KotlinCodeStylePreferences.save(src, prefs)

        val dst = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(dst)
        KotlinCodeStylePreferences.load(prefs, dst)
        assertEquals(80, dst.getCommonSettings(KotlinLanguage.INSTANCE).RIGHT_MARGIN)
    }

    /** WRAP_ON_TYPING persists through save → load cycle. */
    fun testWrapOnTypingRoundTrip() {
        val prefs = freshPrefs()
        val src = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(src)
        src.getCommonSettings(KotlinLanguage.INSTANCE).WRAP_ON_TYPING = 1  // WrapOnTyping.WRAP
        KotlinCodeStylePreferences.save(src, prefs)

        val dst = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(dst)
        KotlinCodeStylePreferences.load(prefs, dst)
        assertEquals(1, dst.getCommonSettings(KotlinLanguage.INSTANCE).WRAP_ON_TYPING)
    }

    /** SOFT_MARGINS (visual guides) persists through save → load cycle. */
    fun testSoftMarginsRoundTrip() {
        val prefs = freshPrefs()
        val src = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(src)
        src.getCommonSettings(KotlinLanguage.INSTANCE).setSoftMargins(listOf(80, 100, 120))
        KotlinCodeStylePreferences.save(src, prefs)

        val dst = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(dst)
        KotlinCodeStylePreferences.load(prefs, dst)
        assertEquals(listOf(80, 100, 120), dst.getCommonSettings(KotlinLanguage.INSTANCE).getSoftMargins())
    }

    /**
     * store() uses merge-store: values from a previous independent panel.store() call on
     * the same prefs are not overwritten when our panel stores.
     */
    fun testMergeStorePreservesOtherPanelValues() {
        val prefs = freshPrefs()

        // Simulate the indent panel writing an indent size.
        val base = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(base)
        KotlinCodeStylePreferences.load(prefs, base)
        base.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT = 7
        KotlinCodeStylePreferences.save(base, prefs)

        // Spacing panel loads and modifies SPACE_AROUND_ASSIGNMENT_OPERATORS.
        val spacingPanel = KotlinSettingsTreePanel(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS) {}
        spacingPanel.load(prefs)
        spacingPanel.settings.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS = false
        spacingPanel.store(prefs)

        // Verify: both the spacing change AND the import count are in the prefs.
        val verify = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(verify)
        KotlinCodeStylePreferences.load(prefs, verify)
        assertFalse(verify.getCommonSettings(KotlinLanguage.INSTANCE).SPACE_AROUND_ASSIGNMENT_OPERATORS)
        assertEquals(7, verify.kotlinCustomSettings.NAME_COUNT_TO_USE_STAR_IMPORT)
    }
}
