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
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntry
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.border.EmptyBorder
import javax.swing.event.ListSelectionListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * Sub-panel for the "Imports" tab in Tools → Options → Kotlin.
 *
 * <p>Mirrors the IntelliJ IDEA Kotlin imports settings panel: two radio-button groups
 * (Top-Level Symbols and Java Statics) for the star-import threshold, a nested-classes
 * checkbox, a "Packages to Use Import with '*'" table, and an "Import Layout" table with
 * an "Import aliases separately" checkbox.
 *
 * @param onChange called whenever any control changes value
 */
class KotlinFormatterImportsPanel(private val onChange: () -> Unit) : JPanel() {

    /** True while load() is populating controls; suppresses onChange callbacks. */
    private var isLoading = false

    private val fireChange: () -> Unit = { if (!isLoading) onChange() }

    private val topLevelSelector = StarImportSelector(
        KotlinCodeStyleSettings.DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT,
        fireChange
    )
    private val javaStaticsSelector = StarImportSelector(
        KotlinCodeStyleSettings.DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS,
        fireChange
    )

    private val importNestedClassesBox = JCheckBox("Insert imports for nested classes")

    private val starImportTable = PackageTablePanel(
        title = "Packages to Use Import with '*'",
        hasUpDown = false,
        onChange = fireChange
    )
    private val importLayoutTable = PackageTablePanel(
        title = "Import Layout",
        hasUpDown = true,
        onChange = fireChange
    )
    private val cbImportAliasesSeparately = JCheckBox("Import aliases separately")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(8, 8, 8, 8)

        add(buildStarImportSection("Top-Level Symbols", topLevelSelector))
        add(Box.createVerticalStrut(6))
        add(buildStarImportSection("Java Statics and Enum Members", javaStaticsSelector))
        add(Box.createVerticalStrut(6))
        add(buildOtherSection())
        add(Box.createVerticalStrut(6))
        add(starImportTable)
        add(Box.createVerticalStrut(6))
        add(buildImportLayoutSection())

        importNestedClassesBox.addActionListener { fireChange() }

        cbImportAliasesSeparately.addItemListener {
            if (!isLoading) {
                val model = importLayoutTable.tableModel
                if (cbImportAliasesSeparately.isSelected) {
                    val entries = importLayoutTable.packageTable.getEntries()
                    if (KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY !in entries) {
                        val row = importLayoutTable.packageTable.entryCount
                        importLayoutTable.packageTable.addEntry(KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY)
                        model.fireTableRowsInserted(row, row)
                    }
                } else {
                    val idx = importLayoutTable.packageTable.getEntries()
                        .indexOf(KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY)
                    if (idx != -1) {
                        importLayoutTable.packageTable.removeEntryAt(idx)
                        model.fireTableRowsDeleted(idx, idx)
                    }
                }
                fireChange()
            }
        }
    }

    private fun buildStarImportSection(title: String, selector: StarImportSelector): JPanel {
        val section = JPanel(GridBagLayout())
        section.border = BorderFactory.createTitledBorder(title)
        section.alignmentX = LEFT_ALIGNMENT

        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 4, 2, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
            weightx = 0.0
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 4
        section.add(selector.rbSingleName, gbc)

        gbc.gridy = 1
        section.add(selector.rbStarAlways, gbc)

        gbc.gridy = 2; gbc.gridwidth = 1
        section.add(selector.rbStarWhen, gbc)
        gbc.gridx = 1; gbc.ipadx = 10
        section.add(selector.spinner, gbc)
        gbc.gridx = 2; gbc.ipadx = 0
        section.add(JLabel("names used"), gbc)

        // filler
        gbc.gridx = 3; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        section.add(JPanel(), gbc)

        return section
    }

    private fun buildOtherSection(): JPanel {
        val section = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        section.border = BorderFactory.createTitledBorder("Other")
        section.alignmentX = LEFT_ALIGNMENT
        section.add(importNestedClassesBox)
        return section
    }

    private fun buildImportLayoutSection(): JPanel {
        val section = JPanel(BorderLayout())
        section.border = BorderFactory.createTitledBorder("Import Layout")
        section.alignmentX = LEFT_ALIGNMENT

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        top.add(cbImportAliasesSeparately)
        section.add(top, BorderLayout.NORTH)
        section.add(importLayoutTable, BorderLayout.CENTER)
        return section
    }

    /**
     * Populates all controls from [prefs] by deserialising the persisted settings.
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
            topLevelSelector.value = ks.NAME_COUNT_TO_USE_STAR_IMPORT
            javaStaticsSelector.value = ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS
            importNestedClassesBox.isSelected = ks.IMPORT_NESTED_CLASSES

            starImportTable.packageTable.copyFrom(ks.PACKAGES_TO_USE_STAR_IMPORTS)
            starImportTable.tableModel.fireTableDataChanged()

            importLayoutTable.packageTable.copyFrom(ks.PACKAGES_IMPORT_LAYOUT)
            importLayoutTable.tableModel.fireTableDataChanged()
            recomputeAliasesCheckbox()
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
        ks.NAME_COUNT_TO_USE_STAR_IMPORT = topLevelSelector.value
        ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = javaStaticsSelector.value
        ks.IMPORT_NESTED_CLASSES = importNestedClassesBox.isSelected
        ks.PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(withoutEmptyPackages(starImportTable.packageTable))
        ks.PACKAGES_IMPORT_LAYOUT.copyFrom(importLayoutTable.packageTable)
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

    private fun recomputeAliasesCheckbox() {
        cbImportAliasesSeparately.isSelected =
            KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY in importLayoutTable.packageTable.getEntries()
    }

    private fun withoutEmptyPackages(src: KotlinPackageEntryTable): KotlinPackageEntryTable {
        val copy = src.clone()
        copy.removeEmptyPackages()
        return copy
    }

    // ─── Test-helper accessors ─────────────────────────────────────────────────

    /** Returns the "Top-level symbols star import threshold" value. */
    fun getNameCountToUseStarImport(): Int = topLevelSelector.value

    /** Returns the "Java statics star import threshold" value. */
    fun getNameCountToUseStarImportForMembers(): Int = javaStaticsSelector.value

    /** Returns whether "Insert imports for nested classes" is selected. */
    fun isImportNestedClasses(): Boolean = importNestedClassesBox.isSelected

    /** Sets the top-level star import threshold without firing onChange. */
    fun setNameCountToUseStarImport(v: Int) {
        topLevelSelector.value = v
    }

    // ─── Inner helpers ─────────────────────────────────────────────────────────

    /**
     * A radio-button group that encodes a star-import threshold:
     * - "Use single name import" → [Int.MAX_VALUE]
     * - "Use import with '*'" → 1
     * - "Use import with '*' when at least N names used" → N (spinner, 2–100)
     */
    private class StarImportSelector(defaultSpinnerValue: Int, private val onChange: () -> Unit) {

        val rbSingleName = JRadioButton("Use single name import")
        val rbStarAlways = JRadioButton("Use import with '*'")
        val rbStarWhen = JRadioButton("Use import with '*' when at least")
        val spinner = JSpinner(SpinnerNumberModel(defaultSpinnerValue.coerceIn(2, 100), 2, 100, 1)).also {
            it.preferredSize = Dimension(60, it.preferredSize.height)
        }

        private val group = ButtonGroup()

        init {
            group.add(rbSingleName)
            group.add(rbStarAlways)
            group.add(rbStarWhen)

            rbStarWhen.isSelected = true
            spinner.isEnabled = true

            val radioListener = java.awt.event.ActionListener {
                spinner.isEnabled = rbStarWhen.isSelected
                onChange()
            }
            rbSingleName.addActionListener(radioListener)
            rbStarAlways.addActionListener(radioListener)
            rbStarWhen.addActionListener(radioListener)
            spinner.addChangeListener { onChange() }
        }

        var value: Int
            get() = when {
                rbSingleName.isSelected -> Int.MAX_VALUE
                rbStarAlways.isSelected -> 1
                else -> spinner.value as Int
            }
            set(v) = when {
                v > 100 -> {
                    rbSingleName.isSelected = true
                    spinner.isEnabled = false
                }
                v < 2 -> {
                    rbStarAlways.isSelected = true
                    spinner.isEnabled = false
                }
                else -> {
                    rbStarWhen.isSelected = true
                    spinner.value = v
                    spinner.isEnabled = true
                }
            }
    }

    /**
     * A panel containing toolbar buttons (+/−/↑/↓) and a [JTable] backed by a
     * [KotlinPackageEntryTable].
     *
     * @param title     titled-border label
     * @param hasUpDown whether to include ↑/↓ move buttons
     * @param onChange  called whenever the table data changes
     */
    private class PackageTablePanel(
        title: String,
        private val hasUpDown: Boolean,
        private val onChange: () -> Unit
    ) : JPanel(BorderLayout()) {

        val packageTable = KotlinPackageEntryTable()
        val tableModel = PackageEntryTableModel(packageTable)
        private val table = JTable(tableModel).also { t ->
            t.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            t.setDefaultRenderer(Boolean::class.javaObjectType, BoolNullableRenderer())
            t.columnModel.getColumn(0).cellRenderer = PackageColumnRenderer(packageTable)
            t.columnModel.getColumn(1).let { col ->
                val w = 120
                col.minWidth = w
                col.maxWidth = w
                col.preferredWidth = w
            }
            t.rowHeight = 20
        }

        private val btnAdd = JButton("+")
        private val btnRemove = JButton("−")
        private val btnUp = JButton("↑")
        private val btnDown = JButton("↓")

        init {
            border = BorderFactory.createTitledBorder(title)
            alignmentX = LEFT_ALIGNMENT

            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2))
            toolbar.add(btnAdd)
            toolbar.add(btnRemove)
            if (hasUpDown) {
                toolbar.add(btnUp)
                toolbar.add(btnDown)
            }

            add(toolbar, BorderLayout.NORTH)
            add(JScrollPane(table).also { it.preferredSize = Dimension(-1, 110) }, BorderLayout.CENTER)

            setupButtons()
            updateButtonState()
            table.selectionModel.addListSelectionListener { updateButtonState() }
        }

        private fun setupButtons() {
            btnAdd.addActionListener {
                val row = if (table.selectedRow >= 0) table.selectedRow + 1 else packageTable.entryCount
                packageTable.insertEntryAt(KotlinPackageEntry("", true), row)
                tableModel.fireTableRowsInserted(row, row)
                table.selectionModel.setSelectionInterval(row, row)
                onChange()
            }
            btnRemove.addActionListener {
                val row = table.selectedRow
                if (row < 0 || packageTable.getEntryAt(row).isSpecial) return@addActionListener
                stopEditing()
                packageTable.removeEntryAt(row)
                tableModel.fireTableRowsDeleted(row, row)
                val newSel = (row - 1).coerceAtLeast(0)
                if (packageTable.entryCount > 0) table.selectionModel.setSelectionInterval(newSel, newSel)
                onChange()
            }
            if (hasUpDown) {
                btnUp.addActionListener {
                    val row = table.selectedRow
                    if (row < 1) return@addActionListener
                    stopEditing()
                    swap(row, row - 1)
                    table.selectionModel.setSelectionInterval(row - 1, row - 1)
                    onChange()
                }
                btnDown.addActionListener {
                    val row = table.selectedRow
                    if (row < 0 || row >= packageTable.entryCount - 1) return@addActionListener
                    stopEditing()
                    swap(row, row + 1)
                    table.selectionModel.setSelectionInterval(row + 1, row + 1)
                    onChange()
                }
            }
        }

        private fun stopEditing() {
            table.cellEditor?.stopCellEditing()
        }

        private fun swap(a: Int, b: Int) {
            val ea = packageTable.getEntryAt(a)
            val eb = packageTable.getEntryAt(b)
            packageTable.setEntryAt(eb, a)
            packageTable.setEntryAt(ea, b)
            tableModel.fireTableRowsUpdated(minOf(a, b), maxOf(a, b))
        }

        private fun updateButtonState() {
            val row = table.selectedRow
            val entry = if (row >= 0 && row < packageTable.entryCount) packageTable.getEntryAt(row) else null
            btnRemove.isEnabled = entry != null && !entry.isSpecial
            if (hasUpDown) {
                btnUp.isEnabled = row > 0
                btnDown.isEnabled = row >= 0 && row < packageTable.entryCount - 1
            }
        }
    }

    /**
     * Table model backed by a [KotlinPackageEntryTable].
     * Column 0: package name (String, editable for non-special rows)
     * Column 1: withSubpackages (Boolean, editable for non-special rows)
     */
    private class PackageEntryTableModel(private val table: KotlinPackageEntryTable) : AbstractTableModel() {

        override fun getColumnCount() = 2
        override fun getRowCount() = table.entryCount
        override fun getColumnName(col: Int) = if (col == 0) "Package" else "With Subpackages"
        override fun getColumnClass(col: Int) =
            if (col == 1) Boolean::class.javaObjectType else String::class.java

        override fun isCellEditable(row: Int, col: Int) = !table.getEntryAt(row).isSpecial

        override fun getValueAt(row: Int, col: Int): Any? {
            val entry = table.getEntryAt(row)
            if (entry.isSpecial) return null
            return if (col == 0) entry.packageName else entry.withSubpackages
        }

        override fun setValueAt(value: Any, row: Int, col: Int) {
            val entry = table.getEntryAt(row)
            val newEntry = when (col) {
                0 -> KotlinPackageEntry(value.toString().removeSuffix(".*").trim(), entry.withSubpackages)
                1 -> KotlinPackageEntry(entry.packageName, value.toString().toBoolean())
                else -> return
            }
            table.setEntryAt(newEntry, row)
            fireTableCellUpdated(row, col)
        }
    }

    /**
     * Renderer for the "Package" column. Displays:
     * - `import all other imports` for [KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY]
     * - `import all alias imports`  for [KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY]
     * - `import X.*`                for regular entries
     */
    private class PackageColumnRenderer(private val table: KotlinPackageEntryTable) : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val entry = table.getEntryAt(row)
            val displayText = when (entry) {
                KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY -> "import all other imports"
                KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY -> "import all alias imports"
                else -> "import ${entry.packageName}.*"
            }
            val c = super.getTableCellRendererComponent(tbl, displayText, isSelected, hasFocus, row, col)
            if (entry.isSpecial && !isSelected) {
                c.foreground = Color.GRAY
            }
            return c
        }
    }

    /**
     * Renderer for the "With Subpackages" boolean column that handles null values
     * (returned for special entries) by showing an empty, disabled cell.
     */
    private class BoolNullableRenderer : TableCellRenderer {

        private val inner = JTable().getDefaultRenderer(Boolean::class.javaObjectType)
        private val empty = DefaultTableCellRenderer()

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): Component =
            if (value == null) empty.getTableCellRendererComponent(tbl, null, isSelected, hasFocus, row, col)
            else inner.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col)
    }
}
