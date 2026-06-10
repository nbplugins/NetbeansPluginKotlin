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
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.awt.Font
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
 * <p>Exposes "Keep maximum blank lines" fields from
 * {@link com.intellij.psi.codeStyle.CommonCodeStyleSettings} and
 * "Minimum blank lines" fields from
 * {@link org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings}.
 *
 * @param onChange called whenever any control changes value; forwarded to the
 *                 parent panel so the Options dialog can track unsaved changes
 */
class KotlinFormatterBlankLinesPanel(private val onChange: () -> Unit) : JPanel(GridBagLayout()) {

    // Keep maximum blank lines (CommonCodeStyleSettings)
    private val keepInDeclarationsSpinner = JSpinner(SpinnerNumberModel(2, 0, 10, 1))
    private val keepInCodeSpinner = JSpinner(SpinnerNumberModel(2, 0, 10, 1))
    private val keepBeforeRbraceSpinner = JSpinner(SpinnerNumberModel(2, 0, 10, 1))

    // Minimum blank lines (CommonCodeStyleSettings + KotlinCodeStyleSettings)
    private val afterClassHeaderSpinner = JSpinner(SpinnerNumberModel(0, 0, 5, 1))
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

        fun addSectionHeader(title: String, row: Int) {
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            val label = JLabel(title)
            label.font = label.font.deriveFont(Font.BOLD)
            add(label, gbc)
            gbc.gridwidth = 1
        }

        fun addRow(label: String, spinner: JSpinner, row: Int) {
            gbc.gridx = 0; gbc.gridy = row; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            add(spinner, gbc)
        }

        addSectionHeader("Keep maximum blank lines", 0)
        addRow("In declarations:", keepInDeclarationsSpinner, 1)
        addRow("In code:", keepInCodeSpinner, 2)
        addRow("Before '}':", keepBeforeRbraceSpinner, 3)

        addSectionHeader("Minimum blank lines", 4)
        addRow("After class header:", afterClassHeaderSpinner, 5)
        addRow("Around 'when' branches with {}:", whenBranchesSpinner, 6)
        addRow("Before declaration with comment or annotation:", declAnnotationSpinner, 7)

        // Filler row to push controls to the top
        gbc.gridx = 0; gbc.gridy = 8; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0; gbc.weighty = 1.0
        add(JPanel(), gbc)

        val fireChange: (Any) -> Unit = { if (!isLoading) onChange() }
        keepInDeclarationsSpinner.addChangeListener(fireChange)
        keepInCodeSpinner.addChangeListener(fireChange)
        keepBeforeRbraceSpinner.addChangeListener(fireChange)
        afterClassHeaderSpinner.addChangeListener(fireChange)
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
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        val cs = tmp.getCommonSettings(KotlinLanguage.INSTANCE)
        val ks = tmp.kotlinCustomSettings
        isLoading = true
        try {
            keepInDeclarationsSpinner.value = cs.KEEP_BLANK_LINES_IN_DECLARATIONS
            keepInCodeSpinner.value = cs.KEEP_BLANK_LINES_IN_CODE
            keepBeforeRbraceSpinner.value = cs.KEEP_BLANK_LINES_BEFORE_RBRACE
            afterClassHeaderSpinner.value = cs.BLANK_LINES_AFTER_CLASS_HEADER
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
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        val cs = tmp.getCommonSettings(KotlinLanguage.INSTANCE)
        val ks = tmp.kotlinCustomSettings
        cs.KEEP_BLANK_LINES_IN_DECLARATIONS = keepInDeclarationsSpinner.value as Int
        cs.KEEP_BLANK_LINES_IN_CODE = keepInCodeSpinner.value as Int
        cs.KEEP_BLANK_LINES_BEFORE_RBRACE = keepBeforeRbraceSpinner.value as Int
        cs.BLANK_LINES_AFTER_CLASS_HEADER = afterClassHeaderSpinner.value as Int
        ks.BLANK_LINES_AROUND_BLOCK_WHEN_BRANCHES = whenBranchesSpinner.value as Int
        ks.BLANK_LINES_BEFORE_DECLARATION_WITH_COMMENT_OR_ANNOTATION_ON_SEPARATE_LINE =
            declAnnotationSpinner.value as Int
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

    /** Returns the current "keep in declarations" spinner value. */
    fun getKeepInDeclarations(): Int = keepInDeclarationsSpinner.value as Int

    /** Returns the current "keep in code" spinner value. */
    fun getKeepInCode(): Int = keepInCodeSpinner.value as Int

    /** Returns the current "keep before '}'" spinner value. */
    fun getKeepBeforeRbrace(): Int = keepBeforeRbraceSpinner.value as Int

    /** Returns the current "after class header" spinner value. */
    fun getAfterClassHeader(): Int = afterClassHeaderSpinner.value as Int

    /** Returns the current "around when branches" spinner value. */
    fun getBlankLinesWhenBranches(): Int = whenBranchesSpinner.value as Int

    /** Returns the current "before decl with annotation" spinner value. */
    fun getBlankLinesDeclWithAnnotation(): Int = declAnnotationSpinner.value as Int

    /** Sets the "keep in declarations" spinner without firing onChange. */
    fun setKeepInDeclarations(value: Int) { keepInDeclarationsSpinner.value = value }

    /** Sets the "keep in code" spinner without firing onChange. */
    fun setKeepInCode(value: Int) { keepInCodeSpinner.value = value }

    /** Sets the "keep before '}'" spinner without firing onChange. */
    fun setKeepBeforeRbrace(value: Int) { keepBeforeRbraceSpinner.value = value }

    /** Sets the "after class header" spinner without firing onChange. */
    fun setAfterClassHeader(value: Int) { afterClassHeaderSpinner.value = value }

    /** Sets the "around when branches" spinner without firing onChange. */
    fun setBlankLinesWhenBranches(value: Int) { whenBranchesSpinner.value = value }

    /** Sets the "before decl with annotation" spinner without firing onChange. */
    fun setBlankLinesDeclWithAnnotation(value: Int) { declAnnotationSpinner.value = value }
}
