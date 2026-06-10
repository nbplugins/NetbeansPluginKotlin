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

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.EventObject
import javax.swing.AbstractCellEditor
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.SpinnerNumberModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellEditor

// ─── Data model ────────────────────────────────────────────────────────────────

/**
 * A single option item displayed as a leaf node in an [OptionTreePanel].
 *
 * @param label display label shown in the tree
 */
sealed class OptionItem(val label: String)

/**
 * A boolean option rendered as a [JCheckBox] leaf.
 */
class BoolItem(label: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : OptionItem(label)

/**
 * A boolean sub-option rendered as an indented [JCheckBox] leaf.
 */
class BoolSubItem(label: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : OptionItem(label)

/**
 * An integer wrap-mode option rendered as a [JComboBox] leaf.
 *
 * @param displayOptions strings shown in the combobox
 * @param storedValues   int values stored for each combo index (parallel to displayOptions)
 */
class WrapItem(
    label: String,
    val displayOptions: Array<String>,
    val storedValues: IntArray,
    val get: () -> Int,
    val set: (Int) -> Unit
) : OptionItem(label) {
    constructor(label: String, get: () -> Int, set: (Int) -> Unit) :
            this(label, WRAP_LABELS, WRAP_VALUES, get, set)

    fun valueToIndex(v: Int): Int = storedValues.indexOfFirst { it == v }.coerceAtLeast(0)
    fun indexToValue(idx: Int): Int = storedValues.getOrElse(idx) { 0 }
}

/**
 * An integer field rendered as a [JSpinner] leaf (e.g. "Hard wrap at").
 *
 * @param min/max bounds for the spinner
 */
class IntFieldItem(
    label: String,
    val get: () -> Int,
    val set: (Int) -> Unit,
    val min: Int = 0,
    val max: Int = 999
) : OptionItem(label)

/**
 * A free-text field rendered as a [JTextField] leaf (e.g. "Visual guides").
 */
class TextFieldItem(
    label: String,
    val get: () -> String,
    val set: (String) -> Unit,
    val columns: Int = 16
) : OptionItem(label)

/**
 * A group of [OptionItem]s displayed as a collapsible branch in an [OptionTreePanel].
 * An empty [label] makes items render directly under the tree root (no group header).
 */
data class OptionGroup(val label: String, val items: List<OptionItem>)

// ─── Wrap-mode default constants ────────────────────────────────────────────────

internal val WRAP_VALUES = intArrayOf(0, 1, 5)
internal val WRAP_LABELS = arrayOf("Don't wrap", "Wrap if long", "Wrap always")

// ─── OptionTreePanel ───────────────────────────────────────────────────────────

/**
 * Reusable pure-Swing [JScrollPane] that shows a [JTree] of [OptionGroup]s, rendering
 * [BoolItem]/[BoolSubItem] leaves as clickable checkboxes, [WrapItem] leaves as
 * single-click [JComboBox] dropdowns, [IntFieldItem] as [JSpinner], and
 * [TextFieldItem] as [JTextField].
 */
class OptionTreePanel(
    groups: List<OptionGroup>,
    private val onChange: () -> Unit
) : JScrollPane() {

    val tree: JTree = JTree(buildModel(groups)).also { configureTree(it) }

    init {
        viewport.view = tree
        expandAll()
    }

    // ─── Tree model ───────────────────────────────────────────────────────────

    private fun buildModel(groups: List<OptionGroup>): DefaultTreeModel {
        val root = DefaultMutableTreeNode("root")
        for (group in groups) {
            if (group.label.isEmpty()) {
                // Ungrouped: items become direct children of the root.
                for (item in group.items) root.add(DefaultMutableTreeNode(item))
            } else {
                val groupNode = DefaultMutableTreeNode(group.label)
                for (item in group.items) groupNode.add(DefaultMutableTreeNode(item))
                root.add(groupNode)
            }
        }
        return DefaultTreeModel(root)
    }

    private fun configureTree(t: JTree) {
        t.isRootVisible = false
        t.showsRootHandles = true
        t.cellRenderer = OptionTreeCellRenderer()
        t.isEditable = true
        t.cellEditor = MultiTypeCellEditor(t)
        t.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val row = t.getRowForLocation(e.x, e.y)
                if (row < 0) return
                val path = t.getPathForRow(row) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val item = node.userObject) {
                    is BoolItem -> {
                        item.set(!item.get())
                        (t.model as DefaultTreeModel).nodeChanged(node)
                        onChange()
                    }
                    is BoolSubItem -> {
                        item.set(!item.get())
                        (t.model as DefaultTreeModel).nodeChanged(node)
                        onChange()
                    }
                    else -> {}
                }
            }
        })
    }

    private fun expandAll() {
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i++)
        }
    }

    // ─── Cell renderer ────────────────────────────────────────────────────────

    private inner class OptionTreeCellRenderer : DefaultTreeCellRenderer() {
        private val checkBox = JCheckBox().apply { isOpaque = false }
        private val subCheckBox = JCheckBox().apply { isOpaque = false }
        private val wrapPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        private val wrapLabel = JLabel()
        private val wrapCombo = JComboBox<String>()
        private val spinnerPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        private val spinnerLabel = JLabel()
        private val spinner = JSpinner(SpinnerNumberModel(0, 0, 999, 1))
        private val textPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        private val textLabel = JLabel()
        private val textField = JTextField()
        private val groupLabel = JLabel()

        init {
            wrapPanel.add(wrapLabel, BorderLayout.CENTER)
            wrapPanel.add(wrapCombo, BorderLayout.EAST)
            spinnerPanel.add(spinnerLabel, BorderLayout.CENTER)
            spinnerPanel.add(spinner, BorderLayout.EAST)
            textPanel.add(textLabel, BorderLayout.CENTER)
            textPanel.add(textField, BorderLayout.EAST)
        }

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            val node = value as? DefaultMutableTreeNode ?: return super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
            return when (val obj = node.userObject) {
                is BoolItem -> {
                    checkBox.text = obj.label
                    checkBox.isSelected = obj.get()
                    checkBox.font = tree.font
                    checkBox
                }
                is BoolSubItem -> {
                    subCheckBox.text = "    ${obj.label}"
                    subCheckBox.isSelected = obj.get()
                    subCheckBox.font = tree.font
                    subCheckBox
                }
                is WrapItem -> {
                    wrapLabel.text = obj.label
                    wrapCombo.removeAllItems()
                    obj.displayOptions.forEach { wrapCombo.addItem(it) }
                    wrapCombo.selectedIndex = obj.valueToIndex(obj.get())
                    wrapPanel.font = tree.font
                    wrapPanel
                }
                is IntFieldItem -> {
                    spinnerLabel.text = obj.label
                    (spinner.model as SpinnerNumberModel).minimum = obj.min
                    (spinner.model as SpinnerNumberModel).maximum = obj.max
                    spinner.value = obj.get()
                    spinnerPanel.font = tree.font
                    spinnerPanel
                }
                is TextFieldItem -> {
                    textLabel.text = obj.label
                    textField.columns = obj.columns
                    textField.text = obj.get()
                    textPanel.font = tree.font
                    textPanel
                }
                else -> {
                    groupLabel.text = obj.toString()
                    groupLabel.font = tree.font.deriveFont(Font.BOLD)
                    groupLabel
                }
            }
        }
    }

    // ─── Multi-type cell editor (dispatches by userObject type) ───────────────

    private inner class MultiTypeCellEditor(private val owner: JTree) : AbstractCellEditor(), TreeCellEditor {
        private val combo = JComboBox<String>()
        private val spinner = JSpinner(SpinnerNumberModel(0, 0, 999, 1))
        private val textField = JTextField()
        private val wrapPanel = JPanel(BorderLayout())
        private val wrapLabel = JLabel()
        private val spinnerPanel = JPanel(BorderLayout())
        private val spinnerLabel = JLabel()
        private val textPanel = JPanel(BorderLayout())
        private val textLabel = JLabel()

        private var currentItem: OptionItem? = null
        private var currentNode: DefaultMutableTreeNode? = null

        init {
            wrapPanel.add(wrapLabel, BorderLayout.CENTER)
            wrapPanel.add(combo, BorderLayout.EAST)
            spinnerPanel.add(spinnerLabel, BorderLayout.CENTER)
            spinnerPanel.add(spinner, BorderLayout.EAST)
            textPanel.add(textLabel, BorderLayout.CENTER)
            textPanel.add(textField, BorderLayout.EAST)

            combo.addActionListener {
                val item = currentItem as? WrapItem ?: return@addActionListener
                item.set(item.indexToValue(combo.selectedIndex))
                stopCellEditing()
            }
            spinner.addChangeListener {
                val item = currentItem as? IntFieldItem ?: return@addChangeListener
                item.set(spinner.value as Int)
                onChange()
            }
            textField.addActionListener {
                val item = currentItem as? TextFieldItem ?: return@addActionListener
                item.set(textField.text)
                stopCellEditing()
            }
        }

        override fun getTreeCellEditorComponent(
            tree: JTree, value: Any, isSelected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int
        ): Component {
            val node = value as? DefaultMutableTreeNode
            currentNode = node
            val item = node?.userObject as? OptionItem
            currentItem = item
            return when (item) {
                is WrapItem -> {
                    combo.removeAllItems()
                    item.displayOptions.forEach { combo.addItem(it) }
                    combo.selectedIndex = item.valueToIndex(item.get())
                    wrapLabel.text = item.label
                    wrapPanel
                }
                is IntFieldItem -> {
                    (spinner.model as SpinnerNumberModel).minimum = item.min
                    (spinner.model as SpinnerNumberModel).maximum = item.max
                    spinner.value = item.get()
                    spinnerLabel.text = item.label
                    spinnerPanel
                }
                is TextFieldItem -> {
                    textField.columns = item.columns
                    textField.text = item.get()
                    textLabel.text = item.label
                    textPanel
                }
                else -> JLabel("")
            }
        }

        override fun stopCellEditing(): Boolean {
            // Commit deferred values (text field on focus loss; spinner already committed via change listener).
            when (val item = currentItem) {
                is TextFieldItem -> { item.set(textField.text); onChange() }
                is IntFieldItem  -> {
                    runCatching { spinner.commitEdit() }
                    item.set(spinner.value as Int)
                    onChange()
                }
                is WrapItem      -> { onChange() }
                else             -> {}
            }
            val result = super.stopCellEditing()
            // Force the renderer to repaint with the freshly stored value so combo/spinner/text
            // don't render as raw Integer/String via the default cell value.
            currentNode?.let { (owner.model as DefaultTreeModel).nodeChanged(it) }
            return result
        }

        // Return the OptionItem itself so the default renderer (if invoked) routes through
        // OptionTreeCellRenderer's type-dispatch and never paints a raw Integer.
        override fun getCellEditorValue(): Any = currentItem ?: ""

        override fun isCellEditable(e: EventObject?): Boolean {
            if (e !is MouseEvent) return false
            val path = owner.getPathForLocation(e.x, e.y) ?: return false
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
            return when (node.userObject) {
                is WrapItem, is IntFieldItem, is TextFieldItem -> true
                else -> false
            }
        }
    }
}
