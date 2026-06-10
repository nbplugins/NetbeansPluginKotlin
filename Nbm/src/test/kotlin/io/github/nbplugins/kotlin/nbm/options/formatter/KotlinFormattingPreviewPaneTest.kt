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
import org.netbeans.api.editor.settings.SimpleValueNames
import org.netbeans.junit.NbTestCase
import java.awt.Dimension
import java.awt.Point
import java.util.prefs.Preferences
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Tests for [KotlinFormattingPreviewPane].
 *
 * <p>All tests run with no open project so that formatting falls back to the
 * unmodified raw code path — this avoids a dependency on a live Kotlin project
 * in the test environment.
 */
class KotlinFormattingPreviewPaneTest : NbTestCase("KotlinFormattingPreviewPaneTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    /** The preview.kt resource must be present and non-empty. */
    fun testRawCodeLoaded() {
        val pane = KotlinFormattingPreviewPane(collectSettings = { _ -> }, projectProvider = { null })
        assertTrue("preview.kt resource must load non-empty code", pane.getRawCode().isNotEmpty())
        assertTrue("raw code must contain Kotlin source", pane.getRawCode().contains("class"))
    }

    /**
     * With no open project [refreshNow] falls back to showing the raw code
     * rather than throwing.
     */
    fun testRefreshWithNoProjectShowsRawCode() {
        val pane = KotlinFormattingPreviewPane(
            collectSettings = { _ -> },
            projectProvider = { null }
        )
        pane.refreshNow()
        assertEquals(pane.getRawCode(), pane.getText())
    }

    /**
     * [collectSettings] is invoked on every refresh — values must always be
     * captured so the editor document properties (tab/indent geometry) can be
     * updated even when no project is available.
     */
    fun testCollectSettingsCalledOnRefresh() {
        var callCount = 0
        val pane = KotlinFormattingPreviewPane(
            collectSettings = { _ -> callCount++ },
            projectProvider = { null }
        )
        pane.refreshNow()
        assertEquals(1, callCount)
    }

    /**
     * After a refresh the editor document must carry tab/indent values matching
     * the temp prefs supplied via [collectSettings], so the editor's tab-stop
     * and indent-guide rendering uses the live spinner values.
     */
    fun testDocumentPropertiesReflectSpinnerValues() {
        val pane = KotlinFormattingPreviewPane(
            collectSettings = { prefs -> writeIndentOptions(prefs, tab = 8, indent = 2, useTab = true) },
            projectProvider = { null }
        )
        pane.refreshNow()
        val doc = pane.getDocument()
        assertEquals(8, doc.getProperty(SimpleValueNames.TAB_SIZE))
        assertEquals(2, doc.getProperty(SimpleValueNames.INDENT_SHIFT_WIDTH))
        assertEquals(false, doc.getProperty(SimpleValueNames.EXPAND_TABS))
    }

    /**
     * When **Use tab character** is unchecked the document's EXPAND_TABS
     * property must become {@code true} so the editor renders spaces and
     * the tab-stop guides disappear.
     */
    fun testExpandTabsTrueWhenUseTabCharacterDisabled() {
        val pane = KotlinFormattingPreviewPane(
            collectSettings = { prefs -> writeIndentOptions(prefs, tab = 4, indent = 4, useTab = false) },
            projectProvider = { null }
        )
        pane.refreshNow()
        assertEquals(true, pane.getDocument().getProperty(SimpleValueNames.EXPAND_TABS))
    }

    /**
     * Refreshing the preview must not jump the scroll viewport back to the top.
     * Sets the viewport to a non-zero Y position before calling refresh and
     * asserts it is unchanged afterwards.
     */
    fun testRefreshPreservesViewportPosition() {
        val pane = KotlinFormattingPreviewPane(
            collectSettings = { _ -> },
            projectProvider = { null }
        )
        // Force layout so the scroll pane has non-zero dimensions and the
        // viewport accepts a non-origin position.
        pane.size = Dimension(400, 100)
        pane.doLayout()
        val scrollPane = pane.getComponent(0) as JScrollPane
        scrollPane.size = Dimension(400, 100)
        scrollPane.doLayout()
        scrollPane.viewport.viewPosition = Point(0, 40)
        val before = scrollPane.viewport.viewPosition
        pane.refreshNow()
        // refresh() restores the viewport via SwingUtilities.invokeLater; pump the EDT
        // by waiting for a trailing no-op runnable to finish before asserting.
        pumpEdt()
        assertEquals("viewport Y must be preserved", before.y, scrollPane.viewport.viewPosition.y)
    }

    /** Helper that blocks until any pending Swing EDT runnables have finished. */
    private fun pumpEdt() {
        SwingUtilities.invokeAndWait { }
    }

    private fun writeIndentOptions(prefs: Preferences, tab: Int, indent: Int, useTab: Boolean) {
        val tmp = CodeStyleSettings()
        val opts = tmp.indentOptions
        opts.TAB_SIZE = tab
        opts.INDENT_SIZE = indent
        opts.CONTINUATION_INDENT_SIZE = indent * 2
        opts.USE_TAB_CHARACTER = useTab
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

}
