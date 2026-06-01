// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
//
// Plain-Swing stub for BaseKotlinImportLayoutPanel: replaces KotlinBundle calls with hardcoded
// English strings so the file compiles without the full IntelliJ plugin classpath.
package org.jetbrains.kotlin.idea.formatter

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.DslComponentProperty
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.table.JBTable
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBInsets
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntry
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable
import org.jetbrains.kotlin.idea.highlighter.KotlinHighlightingColors
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultCellEditor
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Base panel for Kotlin import layout tables.
 *
 * <p>Provides add/remove/move-up/move-down operations on a {@link KotlinPackageEntryTable}
 * displayed in a {@link JBTable}.
 *
 * @param title the titled-border label for the panel
 */
open class BaseKotlinImportLayoutPanel(@Nls title: String) : JPanel(BorderLayout()) {
    val packageTable = KotlinPackageEntryTable()
    val layoutTable = createTableForPackageEntries(packageTable)

    init {
        border = IdeBorderFactory.createTitledBorder(
            title,
            false,
            JBInsets.emptyInsets()
        )
        putClientProperty(DslComponentProperty.VISUAL_PADDINGS, UnscaledGaps.EMPTY)
    }

    /** Adds a new blank package entry after the currently selected row. */
    protected fun addPackage() {
        var row = layoutTable.selectedRow + 1
        if (row < 0) {
            row = packageTable.entryCount
        }
        val entry = KotlinPackageEntry("", true)
        packageTable.insertEntryAt(entry, row)
        refreshTableModel(row)
    }

    /** Removes the currently selected package entry, if it is not a special entry. */
    protected fun removePackage() {
        var row = layoutTable.selectedRow
        if (row < 0) return

        val entry = packageTable.getEntryAt(row)
        if (entry.isSpecial) return

        TableUtil.stopEditing(layoutTable)
        packageTable.removeEntryAt(row)

        val model = layoutTable.model as AbstractTableModel
        model.fireTableRowsDeleted(row, row)

        if (row >= packageTable.entryCount) {
            row--
        }

        if (row >= 0) {
            layoutTable.setRowSelectionInterval(row, row)
        }
    }

    /** Moves the selected package entry one position up in the list. */
    protected fun movePackageUp() {
        val row = layoutTable.selectedRow
        if (row < 1) return

        TableUtil.stopEditing(layoutTable)
        val entry = packageTable.getEntryAt(row)
        val previousEntry = packageTable.getEntryAt(row - 1)
        packageTable.setEntryAt(entry, row - 1)
        packageTable.setEntryAt(previousEntry, row)

        val model = layoutTable.model as AbstractTableModel
        model.fireTableRowsUpdated(row - 1, row)
        layoutTable.setRowSelectionInterval(row - 1, row - 1)
    }

    /** Moves the selected package entry one position down in the list. */
    protected fun movePackageDown() {
        val row = layoutTable.selectedRow
        if (row >= packageTable.entryCount - 1) return

        TableUtil.stopEditing(layoutTable)
        val entry = packageTable.getEntryAt(row)
        val nextEntry = packageTable.getEntryAt(row + 1)
        packageTable.setEntryAt(entry, row + 1)
        packageTable.setEntryAt(nextEntry, row)

        val model = layoutTable.model as AbstractTableModel
        model.fireTableRowsUpdated(row, row + 1)
        layoutTable.setRowSelectionInterval(row + 1, row + 1)
    }

    private fun refreshTableModel(row: Int) {
        val model = layoutTable.model as AbstractTableModel
        model.fireTableRowsInserted(row, row)
        layoutTable.setRowSelectionInterval(row, row)
        TableUtil.editCellAt(layoutTable, row, 0)
        val editorComp = layoutTable.editorComponent
        if (editorComp != null) {
            IdeFocusManager.getGlobalInstance()
                .doWhenFocusSettlesDown { IdeFocusManager.getGlobalInstance().requestFocus(editorComp, true) }
        }
    }

    /** Resizes table columns and installs Kotlin-syntax-colored package cell renderer. */
    protected fun resizeColumns() {
        val packageRenderer: ColoredTableCellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(
                table: JTable,
                value: Any?,
                selected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ) {
                val entry = packageTable.getEntryAt(row)
                val attributes = KotlinHighlightingColors.KEYWORD.defaultAttributes
                append("import", SimpleTextAttributes.fromTextAttributes(attributes))
                append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

                when (entry) {
                    KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY -> append(
                        "all other imports",
                        SimpleTextAttributes.REGULAR_ATTRIBUTES
                    )

                    KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY -> append(
                        "all alias imports",
                        SimpleTextAttributes.REGULAR_ATTRIBUTES
                    )

                    else -> append(
                        "${entry.packageName}.*",
                        SimpleTextAttributes.REGULAR_ATTRIBUTES
                    )
                }
            }
        }

        layoutTable.columnModel.apply {
            getColumn(0).cellRenderer = packageRenderer
            getColumn(1).cellRenderer = BooleanTableCellRenderer()

            fixColumnWidthToHeader(1)
        }
    }

    private fun fixColumnWidthToHeader(columnIndex: Int) {
        with(layoutTable) {
            val column = columnModel.getColumn(columnIndex)
            val width = 15 + tableHeader.getFontMetrics(tableHeader.font).stringWidth(getColumnName(columnIndex))

            column.minWidth = width
            column.maxWidth = width
        }
    }
}

/**
 * Panel for configuring which packages use star imports.
 *
 * <p>Contains a toolbar-decorated table for adding and removing package patterns.
 */
class KotlinStarImportLayoutPanel : BaseKotlinImportLayoutPanel("Packages to Use Import with '*'") {
    init {
        val importLayoutPanel = ToolbarDecorator.createDecorator(layoutTable)
            .setAddAction { addPackage() }
            .setRemoveAction { removePackage() }
            .setButtonComparator("Add", "Remove")
            .setPreferredSize(Dimension(-1, 100))
            .createPanel()

        add(importLayoutPanel, BorderLayout.CENTER)
        resizeColumns()
    }
}

/**
 * Panel for configuring the import order layout.
 *
 * <p>Contains a toolbar-decorated table with add/remove/up/down actions,
 * and a checkbox to import aliases separately.
 */
class KotlinImportOrderLayoutPanel : BaseKotlinImportLayoutPanel("Import Layout") {
    private val cbImportAliasesSeparately = JBCheckBox("Import aliases separately")

    init {
        add(cbImportAliasesSeparately, BorderLayout.NORTH)

        val importLayoutPanel = ToolbarDecorator.createDecorator(layoutTable)
            .addExtraAction(
                object : DumbAwareAction("Add Package", null, IconUtil.addPackageIcon) {
                    init {
                        shortcutSet = CommonShortcuts.getNewForDialogs()
                    }
                    override fun actionPerformed(event: AnActionEvent) {
                        addPackage()
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                }
            )
            .setRemoveAction { removePackage() }
            .setMoveUpAction { movePackageUp() }
            .setMoveDownAction { movePackageDown() }
            .setRemoveActionUpdater {
                val selectedRow = layoutTable.selectedRow
                val entry = if (selectedRow in 0 until packageTable.entryCount) packageTable.getEntryAt(selectedRow) else null

                entry?.isSpecial == false
            }.setButtonComparator("Add Package", "Remove", "Up", "Down")
            .setPreferredSize(Dimension(-1, 100))
            .createPanel()

        add(importLayoutPanel, BorderLayout.CENTER)
        resizeColumns()

        cbImportAliasesSeparately.addItemListener {
            if (areImportAliasesEnabled()) {
                if (KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY !in packageTable.getEntries()) {
                    packageTable.addEntry(KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY)
                    val row = packageTable.entryCount - 1
                    val model = layoutTable.model as AbstractTableModel
                    model.fireTableRowsInserted(row, row)
                    layoutTable.setRowSelectionInterval(row, row)
                }
            } else {
                val entryIndex = packageTable.getEntries().indexOf(KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY)

                if (entryIndex != -1) {
                    val currentIndex = layoutTable.selectedRow
                    packageTable.removeEntryAt(entryIndex)
                    val model = layoutTable.model as AbstractTableModel
                    model.fireTableRowsDeleted(entryIndex, entryIndex)

                    if (currentIndex < entryIndex) {
                        layoutTable.setRowSelectionInterval(currentIndex, currentIndex)
                    } else if (entryIndex > 0) {
                        layoutTable.setRowSelectionInterval(entryIndex - 1, entryIndex - 1)
                    }
                }
            }
        }
    }

    /**
     * Synchronises the "Import aliases separately" checkbox state with the current
     * contents of the package table.
     */
    fun recomputeAliasesCheckbox() {
        cbImportAliasesSeparately.isSelected = KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY in packageTable.getEntries()
    }

    private fun areImportAliasesEnabled(): Boolean {
        return cbImportAliasesSeparately.isSelected
    }
}

/**
 * Creates a {@link JBTable} backed by the given package-entry table.
 *
 * @param packageTable the data model
 * @return the configured table component
 */
fun createTableForPackageEntries(packageTable: KotlinPackageEntryTable): JBTable {
    val names = arrayOf("Package", "With Subpackages")
    val packageNameColumnIndex = 0
    val withSubpackagesColumnIndex = 1

    val dataModel = object : AbstractTableModel() {
        override fun getColumnCount(): Int {
            return names.size
        }

        override fun getRowCount(): Int {
            return packageTable.entryCount
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val entry = packageTable.getEntryAt(rowIndex)
            if (!isCellEditable(rowIndex, columnIndex)) return null

            return when (columnIndex) {
                packageNameColumnIndex -> entry.packageName
                withSubpackagesColumnIndex -> entry.withSubpackages
                else -> throw IllegalArgumentException(columnIndex.toString())
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            val entry = packageTable.getEntryAt(rowIndex)
            return !entry.isSpecial
        }

        override fun getColumnName(column: Int): String {
            return names[column]
        }

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                packageNameColumnIndex -> String::class.java
                withSubpackagesColumnIndex -> Boolean::class.javaObjectType
                else -> throw IllegalArgumentException(columnIndex.toString())
            }
        }

        override fun setValueAt(value: Any, rowIndex: Int, columnIndex: Int) {
            val entry = packageTable.getEntryAt(rowIndex)

            val newEntry = when (columnIndex) {
                packageNameColumnIndex -> KotlinPackageEntry((value as String).trim(), entry.withSubpackages)
                withSubpackagesColumnIndex -> KotlinPackageEntry(entry.packageName, value.toString().toBoolean())
                else -> throw IllegalArgumentException(columnIndex.toString())
            }

            packageTable.setEntryAt(newEntry, rowIndex)
        }
    }

    val result = JBTable(dataModel)
    result.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

    val editor = result.getDefaultEditor(String::class.java)
    if (editor is DefaultCellEditor) editor.clickCountToStart = 1

    return result
}
