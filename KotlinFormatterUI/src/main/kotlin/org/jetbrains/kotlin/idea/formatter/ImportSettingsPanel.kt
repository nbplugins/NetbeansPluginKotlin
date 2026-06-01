// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
//
// Plain-Swing replacement for the DSL-based ImportSettingsPanel / ImportSettingsPanelWrapper.
// The original uses `panel {}` (Kotlin UI DSL) which requires platform-impl.
package org.jetbrains.kotlin.idea.formatter

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBScrollPane
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Wrapper panel for the imports-settings category, shown in the "Imports" tab.
 *
 * <p>Exposes three scalar options from {@code KotlinCodeStyleSettings}:
 * <ul>
 *   <li>{@code NAME_COUNT_TO_USE_STAR_IMPORT} — star import threshold for top-level symbols</li>
 *   <li>{@code NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS} — star import threshold for statics/enums</li>
 *   <li>{@code IMPORT_NESTED_CLASSES} — whether to insert imports for nested classes</li>
 * </ul>
 * The import-order and star-import package tables are delegated to
 * {@link BaseKotlinImportLayoutPanel} subclasses.
 *
 * @param settings the live code-style settings model
 */
class ImportSettingsPanelWrapper(settings: CodeStyleSettings) :
    CodeStyleAbstractPanel(null, null, settings) {

    private val importsPanel = ImportSettingsPanel()
    private val scrollPane = JBScrollPane(importsPanel.panel)

    override fun getRightMargin(): Int = -1

    override fun createHighlighter(scheme: EditorColorsScheme?) =
        throw UnsupportedOperationException()

    override fun getFileType() = throw UnsupportedOperationException()

    override fun getPreviewText(): String? = null

    override fun apply(settings: CodeStyleSettings) =
        importsPanel.apply(settings.kotlinCustomSettings)

    override fun isModified(settings: CodeStyleSettings): Boolean =
        importsPanel.isModified(settings.kotlinCustomSettings)

    override fun getPanel(): JComponent = scrollPane

    override fun resetImpl(settings: CodeStyleSettings) {
        importsPanel.reset(settings.kotlinCustomSettings)
        importOrderLayoutPanel().recomputeAliasesCheckbox()
    }

    private fun importOrderLayoutPanel(): KotlinImportOrderLayoutPanel =
        importsPanel.importOrderLayoutPanel

    override fun getTabTitle(): String = "Imports"
}

/**
 * Inner panel containing all import-option widgets.
 *
 * <p>Provides {@link #apply}, {@link #isModified}, and {@link #reset} methods
 * that mirror the corresponding lifecycle calls from the parent wrapper.
 */
private class ImportSettingsPanel {

    private val cbImportNestedClasses =
        JCheckBox("Insert imports for nested classes")

    private val spTopLevel = JBIntSpinner(
        KotlinCodeStyleSettings.DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT, 1, Int.MAX_VALUE
    )
    private val spMembers = JBIntSpinner(
        KotlinCodeStyleSettings.DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS, 1, Int.MAX_VALUE
    )

    val starImportLayoutPanel = KotlinStarImportLayoutPanel()
    val importOrderLayoutPanel = KotlinImportOrderLayoutPanel()

    val panel: JPanel = JPanel(GridBagLayout()).apply {
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }

        val thresholdsPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Use Star Import")
            val g = GridBagConstraints().apply {
                fill = GridBagConstraints.NONE
                insets = Insets(2, 4, 2, 4)
            }
            g.gridx = 0; g.gridy = 0; g.anchor = GridBagConstraints.WEST
            add(JLabel("Top-level symbols"), g)
            g.gridx = 1
            add(spTopLevel, g)
            g.gridx = 0; g.gridy = 1; g.anchor = GridBagConstraints.WEST
            add(JLabel("Java statics and enum members"), g)
            g.gridx = 1
            add(spMembers, g)
        }
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0
        add(thresholdsPanel, gbc)

        val otherPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Other")
            add(cbImportNestedClasses, BorderLayout.NORTH)
        }
        gbc.gridy = 1
        add(otherPanel, gbc)

        gbc.gridy = 2; gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 0.5
        add(starImportLayoutPanel, gbc)

        gbc.gridy = 3
        add(importOrderLayoutPanel, gbc)
    }

    /**
     * Applies the current UI state to the given Kotlin code-style settings.
     *
     * @param settings the Kotlin code-style settings to update
     */
    fun apply(settings: KotlinCodeStyleSettings) {
        settings.NAME_COUNT_TO_USE_STAR_IMPORT = spTopLevel.number
        settings.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = spMembers.number
        settings.IMPORT_NESTED_CLASSES = cbImportNestedClasses.isSelected
        settings.PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(
            getCopyWithoutEmptyPackages(starImportLayoutPanel.packageTable)
        )
        settings.PACKAGES_IMPORT_LAYOUT.copyFrom(importOrderLayoutPanel.packageTable)
    }

    /**
     * Returns {@code true} if the UI state differs from the given settings.
     *
     * @param settings the Kotlin code-style settings to compare against
     * @return {@code true} if there are unsaved changes
     */
    fun isModified(settings: KotlinCodeStyleSettings): Boolean {
        if (settings.NAME_COUNT_TO_USE_STAR_IMPORT != spTopLevel.number) return true
        if (settings.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS != spMembers.number) return true
        if (settings.IMPORT_NESTED_CLASSES != cbImportNestedClasses.isSelected) return true
        if (isTableModified(getCopyWithoutEmptyPackages(starImportLayoutPanel.packageTable),
                            settings.PACKAGES_TO_USE_STAR_IMPORTS)) return true
        if (isTableModified(importOrderLayoutPanel.packageTable,
                            settings.PACKAGES_IMPORT_LAYOUT)) return true
        return false
    }

    /**
     * Resets the UI to reflect the given settings.
     *
     * @param settings the Kotlin code-style settings to read from
     */
    fun reset(settings: KotlinCodeStyleSettings) {
        spTopLevel.number = settings.NAME_COUNT_TO_USE_STAR_IMPORT
        spMembers.number = settings.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS
        cbImportNestedClasses.isSelected = settings.IMPORT_NESTED_CLASSES
        starImportLayoutPanel.packageTable.copyFrom(settings.PACKAGES_TO_USE_STAR_IMPORTS)
        importOrderLayoutPanel.packageTable.copyFrom(settings.PACKAGES_IMPORT_LAYOUT)
    }

    private fun getCopyWithoutEmptyPackages(table: KotlinPackageEntryTable): KotlinPackageEntryTable {
        val copy = KotlinPackageEntryTable()
        copy.copyFrom(table)
        copy.removeEmptyPackages()
        return copy
    }

    private fun isTableModified(a: KotlinPackageEntryTable, b: KotlinPackageEntryTable): Boolean {
        if (a.entryCount != b.entryCount) return true
        for (i in 0 until a.entryCount) {
            if (a.getEntryAt(i) != b.getEntryAt(i)) return true
        }
        return false
    }
}
