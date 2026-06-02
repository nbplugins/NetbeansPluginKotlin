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
package io.github.nbplugins.kotlin.nbm.options

import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterBlankLinesPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterIndentPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterOtherPanel
import java.awt.BorderLayout
import java.util.prefs.Preferences
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * Root panel for Tools → Options → Kotlin.
 *
 * <p>Contains a tabbed pane with formatter sub-panels. The [onChange] callback
 * is forwarded to each sub-panel so that [KotlinOptionsPanelController] can
 * track whether the user has made unsaved changes.
 *
 * @param onChange called whenever any control in any sub-panel changes value
 */
class KotlinOptionsPanel(private val onChange: () -> Unit) : JPanel(BorderLayout()) {

    private val indentPanel = KotlinFormatterIndentPanel(onChange)
    private val blankLinesPanel = KotlinFormatterBlankLinesPanel(onChange)
    private val otherPanel = KotlinFormatterOtherPanel(onChange)

    init {
        val tabs = JTabbedPane()
        tabs.addTab("Tabs & Indent", indentPanel)
        tabs.addTab("Blank Lines", blankLinesPanel)
        tabs.addTab("Other", otherPanel)
        add(tabs, BorderLayout.CENTER)
    }

    /**
     * Populates all sub-panels from [prefs].
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        indentPanel.load(prefs)
        blankLinesPanel.load(prefs)
        otherPanel.load(prefs)
    }

    /**
     * Writes all sub-panels' current state to [prefs].
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        indentPanel.store(prefs)
        blankLinesPanel.store(prefs)
        otherPanel.store(prefs)
    }
}
