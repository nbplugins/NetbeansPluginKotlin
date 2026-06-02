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
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.prefs.Preferences
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.border.EmptyBorder

/**
 * Sub-panel for "Blank Lines" Kotlin code-style settings shown in
 * Tools → Options → Kotlin → Blank Lines.
 *
 * <p>Exposes two blank-line count fields from
 * {@link org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings} stored
 * under {@link KotlinCodeStylePreferences#PREFS_KEY_KOTLIN}.
 *
 * @param onChange called whenever any control changes value; forwarded to the
 *                 parent panel so the Options dialog can track unsaved changes
 */
class KotlinFormatterBlankLinesPanel(private val onChange: () -> Unit) : JPanel(GridBagLayout()) {

    private val whenBranchesSpinner = JSpinner(SpinnerNumberModel(0, 0, 5, 1))
    private val declAnnotationSpinner = JSpinner(SpinnerNumberModel(1, 0, 5, 1))

    /** True while load() is populating controls; suppresses onChange callbacks. */
    private var isLoading = false

    init {
        border = EmptyBorder(8, 8, 8, 8)
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            anchor = GridBagConstraints.WEST
        }

        fun addRow(label: String, spinner: JSpinner, row: Int) {
            gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(spinner, gbc)
        }

        addRow("Around 'when' branches with block body:", whenBranchesSpinner, 0)
        addRow("Before declaration with comment/annotation:", declAnnotationSpinner, 1)

        // Filler row to push controls to the top
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0; gbc.weighty = 1.0
        add(JPanel(), gbc)

        val fireChange: (Any) -> Unit = { if (!isLoading) onChange() }
        whenBranchesSpinner.addChangeListener(fireChange)
        declAnnotationSpinner.addChangeListener(fireChange)
    }

    /**
     * Populates spinners from [prefs] by deserializing Kotlin code-style settings.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        val ks = tmp.kotlinCustomSettings
        isLoading = true
        try {
            whenBranchesSpinner.value = ks.BLANK_LINES_AROUND_BLOCK_WHEN_BRANCHES
            declAnnotationSpinner.value =
                ks.BLANK_LINES_BEFORE_DECLARATION_WITH_COMMENT_OR_ANNOTATION_ON_SEPARATE_LINE
        } finally {
            isLoading = false
        }
    }

    /**
     * Writes the spinners' current state to [prefs], merging with any existing
     * settings so that other fields are not overwritten.
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        val ks = tmp.kotlinCustomSettings
        ks.BLANK_LINES_AROUND_BLOCK_WHEN_BRANCHES = whenBranchesSpinner.value as Int
        ks.BLANK_LINES_BEFORE_DECLARATION_WITH_COMMENT_OR_ANNOTATION_ON_SEPARATE_LINE =
            declAnnotationSpinner.value as Int
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

    /** Returns the current "around when branches" spinner value. */
    fun getBlankLinesWhenBranches(): Int = whenBranchesSpinner.value as Int

    /** Returns the current "before decl with annotation" spinner value. */
    fun getBlankLinesDeclWithAnnotation(): Int = declAnnotationSpinner.value as Int

    /** Sets the "around when branches" spinner without firing onChange. */
    fun setBlankLinesWhenBranches(value: Int) { whenBranchesSpinner.value = value }

    /** Sets the "before decl with annotation" spinner without firing onChange. */
    fun setBlankLinesDeclWithAnnotation(value: Int) { declAnnotationSpinner.value = value }
}
