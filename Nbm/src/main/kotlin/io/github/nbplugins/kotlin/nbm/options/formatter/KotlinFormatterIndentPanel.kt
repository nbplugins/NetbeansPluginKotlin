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
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.prefs.Preferences
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.border.EmptyBorder

/**
 * Sub-panel for "Tabs & Indent" Kotlin code-style settings shown in
 * Tools → Options → Kotlin → Tabs & Indent.
 *
 * <p>Exposes four indent options that map to
 * {@link com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions} stored
 * under {@link KotlinCodeStylePreferences#PREFS_KEY_INDENT}.
 *
 * @param onChange called whenever any control changes value; forwarded to the
 *                 parent panel so the Options dialog can track unsaved changes
 */
class KotlinFormatterIndentPanel(private val onChange: () -> Unit) : JPanel(GridBagLayout()) {

    private val tabSizeSpinner = JSpinner(SpinnerNumberModel(4, 1, 20, 1))
    private val indentSizeSpinner = JSpinner(SpinnerNumberModel(4, 1, 20, 1))
    private val continuationIndentSpinner = JSpinner(SpinnerNumberModel(8, 1, 20, 1))
    private val useTabsCheckbox = JCheckBox("Use tab character")
    private val smartTabsCheckbox = JCheckBox("Smart tabs")
    private val keepIndentsCheckbox = JCheckBox("Keep indents on empty lines")

    /** True while load() is populating controls; suppresses onChange callbacks. */
    private var isLoading = false

    init {
        border = EmptyBorder(8, 8, 8, 8)
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
        }

        fun addRow(label: String, component: javax.swing.JComponent, row: Int) {
            gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(component, gbc)
        }

        // Layout matches IDEA's SmartIndentOptionsEditor order:
        // Use tab character → Smart tabs (indented) → spinners → Keep indents on empty lines
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        add(useTabsCheckbox, gbc)
        gbc.gridwidth = 1

        // Smart tabs is a sub-item of "Use tab character" — add left indent via inset
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        gbc.insets = Insets(4, 24, 4, 4)  // extra left indent
        add(smartTabsCheckbox, gbc)
        gbc.gridwidth = 1
        gbc.insets = Insets(4, 4, 4, 4)  // restore normal insets

        addRow("Tab size:", tabSizeSpinner, 2)
        addRow("Indent:", indentSizeSpinner, 3)
        addRow("Continuation indent:", continuationIndentSpinner, 4)

        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        add(keepIndentsCheckbox, gbc)
        gbc.gridwidth = 1

        // Filler row to push controls to the top
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0; gbc.weighty = 1.0
        add(JPanel(), gbc)

        val fireChange: (Any) -> Unit = { if (!isLoading) onChange() }
        tabSizeSpinner.addChangeListener(fireChange)
        indentSizeSpinner.addChangeListener(fireChange)
        continuationIndentSpinner.addChangeListener(fireChange)
        useTabsCheckbox.addActionListener {
            smartTabsCheckbox.isEnabled = useTabsCheckbox.isSelected
            if (!isLoading) onChange()
        }
        smartTabsCheckbox.addActionListener { if (!isLoading) onChange() }
        keepIndentsCheckbox.addActionListener { if (!isLoading) onChange() }

        smartTabsCheckbox.isEnabled = false  // enabled only when "Use tab character" is on
    }

    /**
     * Populates spinners and checkbox from [prefs] by deserializing indent options.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        val opts = tmp.indentOptions
        isLoading = true
        try {
            tabSizeSpinner.value = opts.TAB_SIZE
            indentSizeSpinner.value = opts.INDENT_SIZE
            continuationIndentSpinner.value = opts.CONTINUATION_INDENT_SIZE
            useTabsCheckbox.isSelected = opts.USE_TAB_CHARACTER
            smartTabsCheckbox.isSelected = opts.SMART_TABS
            smartTabsCheckbox.isEnabled = opts.USE_TAB_CHARACTER
            keepIndentsCheckbox.isSelected = opts.KEEP_INDENTS_ON_EMPTY_LINES
        } finally {
            isLoading = false
        }
    }

    /**
     * Writes the controls' current state to [prefs], merging with any existing
     * settings so that other fields (e.g. Kotlin custom settings) are not overwritten.
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        val opts = tmp.indentOptions
        opts.TAB_SIZE = tabSizeSpinner.value as Int
        opts.INDENT_SIZE = indentSizeSpinner.value as Int
        opts.CONTINUATION_INDENT_SIZE = continuationIndentSpinner.value as Int
        opts.USE_TAB_CHARACTER = useTabsCheckbox.isSelected
        opts.SMART_TABS = smartTabsCheckbox.isSelected
        opts.KEEP_INDENTS_ON_EMPTY_LINES = keepIndentsCheckbox.isSelected
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

    /** Returns the current tab size spinner value. */
    fun getTabSize(): Int = tabSizeSpinner.value as Int

    /** Returns the current indent size spinner value. */
    fun getIndentSize(): Int = indentSizeSpinner.value as Int

    /** Returns the current continuation indent spinner value. */
    fun getContinuationIndentSize(): Int = continuationIndentSpinner.value as Int

    /** Returns whether the "Use tab character" checkbox is selected. */
    fun isUseTabCharacter(): Boolean = useTabsCheckbox.isSelected

    /** Sets the tab size spinner without firing onChange. */
    fun setTabSize(value: Int) { tabSizeSpinner.value = value }

    /** Sets the indent size spinner without firing onChange. */
    fun setIndentSize(value: Int) { indentSizeSpinner.value = value }

    /** Sets the continuation indent spinner without firing onChange. */
    fun setContinuationIndentSize(value: Int) { continuationIndentSpinner.value = value }

    /** Sets the "Use tab character" checkbox without firing onChange. */
    fun setUseTabCharacter(selected: Boolean) { useTabsCheckbox.isSelected = selected }

    /** Returns whether the "Smart tabs" checkbox is selected. */
    fun isSmartTabs(): Boolean = smartTabsCheckbox.isSelected

    /** Returns whether the "Keep indents on empty lines" checkbox is selected. */
    fun isKeepIndentsOnEmptyLines(): Boolean = keepIndentsCheckbox.isSelected

    /** Sets the "Smart tabs" checkbox without firing onChange. */
    fun setSmartTabs(selected: Boolean) { smartTabsCheckbox.isSelected = selected }

    /** Sets the "Keep indents on empty lines" checkbox without firing onChange. */
    fun setKeepIndentsOnEmptyLines(selected: Boolean) { keepIndentsCheckbox.isSelected = selected }
}
