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
import org.netbeans.api.project.ui.OpenProjects
import java.awt.BorderLayout
import java.awt.Font
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer

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
 */
class KotlinFormattingPreviewPane(
    private val collectSettings: (Preferences) -> Unit
) : JPanel(BorderLayout()) {

    private val rawCode: String = loadRawCode()
    private val textArea = JTextArea(rawCode).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        caretPosition = 0
    }
    private var timer: Timer? = null

    init {
        add(JScrollPane(textArea), BorderLayout.CENTER)
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
    fun getText(): String = textArea.text

    private fun refresh() {
        val project = OpenProjects.getDefault().openProjects.firstOrNull() ?: run {
            textArea.text = rawCode
            textArea.caretPosition = 0
            return
        }
        val tempPrefs = MapPreferences()
        collectSettings(tempPrefs)
        KotlinCodeStylePreferences.loadIntoGlobal(tempPrefs)
        val formatted = try {
            KotlinFormatterUtils.formatCode(rawCode, "preview.kt", project, "\n")
        } catch (_: Exception) {
            rawCode
        }
        SwingUtilities.invokeLater {
            textArea.text = formatted
            textArea.caretPosition = 0
        }
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
