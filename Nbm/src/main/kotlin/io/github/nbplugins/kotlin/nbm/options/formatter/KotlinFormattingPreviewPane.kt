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
import org.netbeans.api.editor.mimelookup.MimeLookup
import org.netbeans.api.editor.mimelookup.MimePath
import org.netbeans.api.editor.settings.SimpleValueNames
import org.netbeans.api.project.ui.OpenProjects
import java.awt.BorderLayout
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.text.Document

/**
 * Read-only preview pane that shows how the current formatter settings affect
 * a representative Kotlin code sample ([preview.kt][io.github.nbplugins.kotlin.nbm]).
 *
 * <p>Call [scheduleRefresh] whenever any setting changes. The pane debounces
 * updates with a 300 ms Swing timer so rapid spinner changes do not trigger a
 * reformat on every event.
 *
 * <p>If no Kotlin project is currently open the pane shows the unformatted
 * sample because formatting requires a live PSI factory (which needs a project).
 *
 * @param collectSettings called with a temporary [Preferences] node; the caller
 *                        must write the current panel state into that node so
 *                        the preview picks it up via
 *                        [KotlinCodeStylePreferences.loadIntoGlobal]
 * @param projectProvider returns the project to use for formatting, or null when
 *                        no project is available; defaults to the first open project
 */
class KotlinFormattingPreviewPane(
    private val collectSettings: (Preferences) -> Unit,
    private val projectProvider: () -> org.netbeans.api.project.Project? =
        { OpenProjects.getDefault().openProjects.firstOrNull() }
) : JPanel(BorderLayout()) {

    private val LOG = Logger.getLogger(KotlinFormattingPreviewPane::class.java.name)

    private val rawCode: String = loadRawCode()

    private val editorPane = JEditorPane().apply {
        val kit = MimeLookup.getLookup(MimePath.parse("text/x-kotlin"))
            .lookup(javax.swing.text.EditorKit::class.java)
        if (kit != null) editorKit = kit
        isEditable = false
        text = rawCode
        caretPosition = 0
    }
    private val scrollPane = JScrollPane(editorPane)
    private var timer: Timer? = null

    init {
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Schedules a preview refresh after a 300 ms debounce.
     *
     * Safe to call from the EDT on every control-change event; rapid calls
     * coalesce into a single reformat.
     */
    fun scheduleRefresh() {
        timer?.stop()
        timer = Timer(300) { refresh() }.also {
            it.isRepeats = false
            it.start()
        }
    }

    /**
     * Performs the preview refresh immediately on the calling thread.
     *
     * Exposed for unit tests; production code should call [scheduleRefresh].
     */
    fun refreshNow() = refresh()

    /** Returns the raw (unformatted) preview source. For tests only. */
    fun getRawCode(): String = rawCode

    /** Returns the current text shown in the preview area. For tests only. */
    fun getText(): String = editorPane.text

    /** Returns the editor pane's document. For tests only. */
    fun getDocument(): Document = editorPane.document

    private fun refresh() {
        val tempPrefs = MapPreferences()
        collectSettings(tempPrefs)
        val tempSettings = CodeStyleSettings().also {
            KotlinFormatterUtils.registerKotlinProvider(it)
            KotlinCodeStylePreferences.load(tempPrefs, it)
        }
        // Push indent geometry onto the editor document so that the NetBeans EditorKit's
        // indent-guide rendering picks up live spinner values (the renderer reads these
        // keys via IndentUtils.tabSize/isExpandTabs/indentLevelSize). EXPAND_TABS keeps the
        // checkbox value so guides appear when "Use tab character" is on, disappear when off.
        val opts = tempSettings.indentOptions
        val doc = editorPane.document
        doc.putProperty(SimpleValueNames.TAB_SIZE, opts.TAB_SIZE)
        doc.putProperty(SimpleValueNames.EXPAND_TABS, !opts.USE_TAB_CHARACTER)
        doc.putProperty(SimpleValueNames.INDENT_SHIFT_WIDTH, opts.INDENT_SIZE)

        // Force the formatter to always emit spaces. The preview text must look identical
        // whether "Use tab character" is on or off; only the indent-guide visibility (driven
        // by EXPAND_TABS above) should change.
        tempSettings.indentOptions.USE_TAB_CHARACTER = false

        val project = projectProvider()
        val formatted = if (project == null) {
            rawCode
        } else {
            try {
                KotlinFormatterUtils.formatCodeWithSettings(rawCode, "preview.kt", project, "\n", tempSettings)
            } catch (_: Exception) { rawCode }
        }
        val viewPos = scrollPane.viewport.viewPosition
        val savedCaret = editorPane.caretPosition
        val viewSize = scrollPane.viewport.viewSize
        LOG.log(Level.INFO, "preview refresh: viewport before = {0}, caret = {1}, viewSize = {2}",
                arrayOf(viewPos, savedCaret, viewSize))
        editorPane.text = formatted
        LOG.log(Level.INFO, "preview refresh: viewport just after setText = {0}, viewSize = {1}",
                arrayOf(scrollPane.viewport.viewPosition, scrollPane.viewport.viewSize))
        // Pin caret to 0 so any caret-driven "ensure visible" scroll lands at the top,
        // not at the end of the new text (which is what JTextComponent.setText leaves it at).
        editorPane.caretPosition = 0
        // Restore viewport via nested invokeLater so we run after any BaseCaret/View layout
        // events that queue their own invokeLater calls — those would otherwise scroll the
        // viewport back to the caret after our first restore.
        SwingUtilities.invokeLater {
            scrollPane.viewport.viewPosition = viewPos
            LOG.log(Level.INFO, "preview refresh: viewport restored (1st pass) = {0}",
                    scrollPane.viewport.viewPosition)
            SwingUtilities.invokeLater {
                scrollPane.viewport.viewPosition = viewPos
                LOG.log(Level.INFO, "preview refresh: viewport restored (2nd pass) = {0}, viewSize = {1}",
                        arrayOf(scrollPane.viewport.viewPosition, scrollPane.viewport.viewSize))
            }
        }
        editorPane.repaint()
    }

    private fun loadRawCode(): String =
        KotlinFormattingPreviewPane::class.java
            .getResourceAsStream("/io/github/nbplugins/kotlin/nbm/preview.kt")
            ?.bufferedReader()?.readText() ?: ""

    /** In-memory [Preferences] backed by a [ConcurrentHashMap]. Used for temporary settings. */
    private class MapPreferences : AbstractPreferences(null, "") {
        private val map = ConcurrentHashMap<String, String>()

        override fun putSpi(key: String, value: String) { map[key] = value }
        override fun getSpi(key: String): String? = map[key]
        override fun removeSpi(key: String) { map.remove(key) }
        override fun removeNodeSpi() {}
        override fun keysSpi(): Array<String> = map.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = emptyArray()
        override fun childSpi(name: String): AbstractPreferences = MapPreferences()
        override fun syncSpi() {}
        override fun flushSpi() {}
    }
}
