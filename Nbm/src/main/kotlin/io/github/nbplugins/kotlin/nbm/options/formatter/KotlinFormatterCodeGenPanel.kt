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
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.awt.Component
import java.util.prefs.Preferences
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.border.EmptyBorder
import javax.swing.border.TitledBorder

/**
 * Sub-panel for the "Code Generation" Kotlin code-style tab shown in
 * Tools → Options → Kotlin → Code Generation.
 *
 * <p>Mirrors the IDEA layout: a "Comment Code" group with five checkboxes.
 * "Enforce on reformat" is indented under "Add a space at line comment start"
 * and is disabled whenever "Add a space at line comment start" is unchecked.
 *
 * @param onChange called whenever any interactive control changes value
 */
class KotlinFormatterCodeGenPanel(private val onChange: () -> Unit) : JPanel() {

    private val cbLineAtFirstColumn  = JCheckBox("Line comment at first column")
    private val cbLineAddSpace       = JCheckBox("Add a space at line comment start")
    internal val cbLineEnforce       = JCheckBox("Enforce on reformat")
    private val cbBlockAtFirstColumn = JCheckBox("Block comment at first column")
    private val cbBlockAddSpace      = JCheckBox("Add spaces around block comments")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(8, 8, 8, 8)

        val group = JPanel()
        group.layout = BoxLayout(group, BoxLayout.Y_AXIS)
        group.border = TitledBorder("Comment Code")
        group.alignmentX = Component.LEFT_ALIGNMENT

        cbLineEnforce.border = EmptyBorder(0, 20, 0, 0)

        listOf(cbLineAtFirstColumn, cbLineAddSpace, cbLineEnforce, cbBlockAtFirstColumn, cbBlockAddSpace).forEach {
            it.alignmentX = Component.LEFT_ALIGNMENT
            group.add(it)
        }

        add(group)

        cbLineAddSpace.addActionListener {
            updateEnforceEnabled()
            onChange()
        }
        cbLineAtFirstColumn.addActionListener { onChange() }
        cbLineEnforce.addActionListener { onChange() }
        cbBlockAtFirstColumn.addActionListener { onChange() }
        cbBlockAddSpace.addActionListener { onChange() }
    }

    /**
     * Populates the controls from [prefs] by deserializing into a temporary
     * settings object and reading the commenter fields from the Kotlin common settings.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        val common = tmp.getCommonSettings(KotlinLanguage.INSTANCE)
        cbLineAtFirstColumn.isSelected  = common.LINE_COMMENT_AT_FIRST_COLUMN
        cbLineAddSpace.isSelected       = common.LINE_COMMENT_ADD_SPACE
        cbLineEnforce.isSelected        = common.LINE_COMMENT_ADD_SPACE_ON_REFORMAT
        cbBlockAtFirstColumn.isSelected = common.BLOCK_COMMENT_AT_FIRST_COLUMN
        cbBlockAddSpace.isSelected      = common.BLOCK_COMMENT_ADD_SPACE
        updateEnforceEnabled()
    }

    /**
     * Writes the controls' current state to [prefs], merging with any existing
     * settings so that other fields are not overwritten.
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        val common = tmp.getCommonSettings(KotlinLanguage.INSTANCE)
        common.LINE_COMMENT_AT_FIRST_COLUMN    = cbLineAtFirstColumn.isSelected
        common.LINE_COMMENT_ADD_SPACE          = cbLineAddSpace.isSelected
        common.LINE_COMMENT_ADD_SPACE_ON_REFORMAT = cbLineEnforce.isSelected
        common.BLOCK_COMMENT_AT_FIRST_COLUMN   = cbBlockAtFirstColumn.isSelected
        common.BLOCK_COMMENT_ADD_SPACE         = cbBlockAddSpace.isSelected
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

    /** "Enforce on reformat" is enabled only when "Add a space" is checked. */
    private fun updateEnforceEnabled() {
        cbLineEnforce.isEnabled = cbLineAddSpace.isSelected
    }
}
