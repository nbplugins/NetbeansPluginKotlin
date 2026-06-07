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
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.prefs.Preferences
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Sub-panel for the "Imports" tab in Tools → Options → Kotlin.
 *
 * <p>Shows two spinners for star-import thresholds and a checkbox for nested class imports,
 * matching IDEA's Kotlin code-style Imports panel.
 *
 * @param onChange called whenever any control changes value
 */
class KotlinFormatterImportsPanel(private val onChange: () -> Unit) : JPanel() {

    /** True while load() is populating controls; suppresses onChange callbacks. */
    private var isLoading = false

    private val fireChange: () -> Unit = { if (!isLoading) onChange() }

    private var nameCountToUseStarImport = 5
    private var nameCountToUseStarImportForMembers = 3
    private var importNestedClasses = false

    private val starImportSpinner = JSpinner(SpinnerNumberModel(5, 1, 100, 1))
    private val memberStarImportSpinner = JSpinner(SpinnerNumberModel(3, 1, 100, 1))
    private val importNestedClassesBox = JCheckBox("Import nested classes with '*' imports")

    init {
        layout = GridBagLayout()
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.WEST
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        add(JLabel("Top-level symbols: use import with '*' when more than N names used:"), gbc)
        gbc.gridx = 2; gbc.gridwidth = 1
        add(starImportSpinner, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2
        add(JLabel("Java statics and enum members: use import with '*' when more than N names used:"), gbc)
        gbc.gridx = 2; gbc.gridwidth = 1
        add(memberStarImportSpinner, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3
        add(importNestedClassesBox, gbc)

        starImportSpinner.addChangeListener {
            nameCountToUseStarImport = (starImportSpinner.value as Int)
            fireChange()
        }
        memberStarImportSpinner.addChangeListener {
            nameCountToUseStarImportForMembers = (memberStarImportSpinner.value as Int)
            fireChange()
        }
        importNestedClassesBox.addActionListener {
            importNestedClasses = importNestedClassesBox.isSelected
            fireChange()
        }
    }

    /**
     * Populates all controls from [prefs] by deserializing the persisted settings.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        isLoading = true
        try {
            val ks = tmp.kotlinCustomSettings
            nameCountToUseStarImport = ks.NAME_COUNT_TO_USE_STAR_IMPORT
            nameCountToUseStarImportForMembers = ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS
            importNestedClasses = ks.IMPORT_NESTED_CLASSES
            starImportSpinner.value = nameCountToUseStarImport
            memberStarImportSpinner.value = nameCountToUseStarImportForMembers
            importNestedClassesBox.isSelected = importNestedClasses
        } finally {
            isLoading = false
        }
    }

    /**
     * Writes the controls' current state to [prefs], merging with any existing settings so
     * that other fields (e.g. Wrapping panel settings) are not overwritten.
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        val ks = tmp.kotlinCustomSettings
        ks.NAME_COUNT_TO_USE_STAR_IMPORT = nameCountToUseStarImport
        ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = nameCountToUseStarImportForMembers
        ks.IMPORT_NESTED_CLASSES = importNestedClasses
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

    // ─── Test-helper accessors ─────────────────────────────────────────────────

    /** Returns the "Top-level symbols star import threshold" value. */
    fun getNameCountToUseStarImport(): Int = nameCountToUseStarImport

    /** Returns the "Java statics star import threshold" value. */
    fun getNameCountToUseStarImportForMembers(): Int = nameCountToUseStarImportForMembers

    /** Returns whether "Import nested classes" is selected. */
    fun isImportNestedClasses(): Boolean = importNestedClasses

    /** Sets the star import threshold without firing onChange. */
    fun setNameCountToUseStarImport(v: Int) {
        nameCountToUseStarImport = v
        starImportSpinner.value = v
    }
}
