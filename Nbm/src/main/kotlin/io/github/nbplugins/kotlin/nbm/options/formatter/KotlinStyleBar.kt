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
import org.jetbrains.kotlin.idea.formatter.KotlinObsoleteStyleGuide
import org.jetbrains.kotlin.idea.formatter.KotlinOfficialStyleGuide
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSeparator
import javax.swing.filechooser.FileNameExtensionFilter

// -------------------------------------------------------------------------
// Combo model types
// -------------------------------------------------------------------------

/**
 * Sealed hierarchy representing an entry in the scheme combo box.
 *
 * <p>The combo contains custom scheme entries (alphabetically sorted) followed
 * by a visual separator and then the three built-in style entries.
 */
sealed class SchemeEntry

/**
 * A named custom scheme stored in [KotlinSchemeManager].
 *
 * @param name the display name of the scheme
 */
data class CustomSchemeEntry(val name: String) : SchemeEntry()

/** Visual separator between custom and built-in entries; not selectable. */
object SeparatorEntry : SchemeEntry()

/**
 * A built-in (non-editable) code-style preset entry.
 *
 * @param style the backing [BuiltInStyle] constant
 */
data class BuiltInSchemeEntry(val style: BuiltInStyle) : SchemeEntry()

/**
 * Built-in code-style presets shown below the separator in the scheme combo.
 *
 * @param title      the human-readable name shown in the combo
 * @param codeStyleId the value stored in [KotlinCodeStyleSettings.CODE_STYLE_DEFAULTS],
 *                    or {@code null} for the "IDE Defaults" entry
 */
enum class BuiltInStyle(val title: String, val codeStyleId: String?) {
    OFFICIAL(KotlinOfficialStyleGuide.CODE_STYLE_TITLE, KotlinOfficialStyleGuide.CODE_STYLE_ID),
    OBSOLETE(KotlinObsoleteStyleGuide.CODE_STYLE_TITLE, KotlinObsoleteStyleGuide.CODE_STYLE_ID),
    DEFAULTS("IDE Defaults", null)
}

// -------------------------------------------------------------------------
// KotlinStyleBar
// -------------------------------------------------------------------------

/**
 * Toolbar panel shown at the top of Tools → Options → Kotlin.
 *
 * <p>Contains a "Scheme:" label, a combo box listing custom schemes and built-in
 * presets, and a gear button (⚙) that opens a context-sensitive popup menu for
 * scheme management operations (Duplicate, Rename, Delete, Restore Defaults,
 * Export…, Import…).
 *
 * @param onStyleApplied          called with merged [CodeStyleSettings] when the user
 *                                selects a scheme or restores defaults
 * @param currentSettingsProvider returns a [CodeStyleSettings] with the panel's current
 *                                (possibly unsaved) state; used as the base for applying
 *                                style presets so that fields the preset does not explicitly
 *                                define are preserved. DEFAULTS ignores this and always
 *                                starts from a blank [CodeStyleSettings].
 * @param onSchemeListChanged     called after Duplicate, Rename, Delete, or Import
 *                                operations so the caller can update its tracking of
 *                                the current scheme name
 */
class KotlinStyleBar(
    private val onStyleApplied: (CodeStyleSettings) -> Unit,
    private val currentSettingsProvider: () -> CodeStyleSettings = {
        CodeStyleSettings().also { KotlinCodeStylePreferences.load(KotlinCodeStylePreferences.prefs(), it) }
    },
    private val onSchemeListChanged: (newName: String?) -> Unit = {}
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    private val combo = JComboBox<SchemeEntry>()

    /** True while [setCurrentScheme] is updating the combo; suppresses the action listener. */
    private var isLoading = false

    /** The scheme name currently shown in the combo (before any uncommitted edit). */
    private var currentScheme: SchemeEntry? = null

    init {
        add(JLabel("Scheme:"))
        combo.renderer = SchemeEntryRenderer()
        rebuildCombo(selectedEntry = null)
        add(combo)

        val gearButton = JButton("⚙")
        gearButton.toolTipText = "Scheme actions"
        gearButton.preferredSize = Dimension(28, gearButton.preferredSize.height)
        gearButton.addActionListener { showGearMenu(gearButton) }
        add(gearButton)

        combo.addActionListener {
            if (!isLoading) {
                val selected = combo.selectedItem as? SchemeEntry ?: return@addActionListener
                if (selected is SeparatorEntry) return@addActionListener
                applySelected(selected)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Combo population
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the combo model from [KotlinSchemeManager] and built-in styles.
     * Tries to re-select [selectedEntry]; falls back to the first built-in entry.
     *
     * @param selectedEntry the entry to select after rebuild, or {@code null} for the default
     */
    private fun rebuildCombo(selectedEntry: SchemeEntry?) {
        isLoading = true
        try {
            combo.removeAllItems()
            val customNames = KotlinSchemeManager.listCustomSchemeNames()
            customNames.forEach { combo.addItem(CustomSchemeEntry(it)) }
            if (customNames.isNotEmpty()) combo.addItem(SeparatorEntry)
            BuiltInStyle.entries.forEach { combo.addItem(BuiltInSchemeEntry(it)) }

            val toSelect = when {
                selectedEntry != null -> selectedEntry
                else -> BuiltInSchemeEntry(BuiltInStyle.OFFICIAL)
            }
            combo.selectedItem = toSelect
            currentScheme = combo.selectedItem as? SchemeEntry
        } finally {
            isLoading = false
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Synchronises the combo selection with [codeStyleId] and [customName] without
     * firing the action listener. Call this from [KotlinOptionsPanel.load].
     *
     * <p>If [customName] is non-null the matching [CustomSchemeEntry] is selected;
     * otherwise the built-in entry whose [BuiltInStyle.codeStyleId] matches [codeStyleId]
     * is selected (falling back to [BuiltInStyle.OFFICIAL]).
     *
     * @param codeStyleId  the value of [KotlinCodeStyleSettings.CODE_STYLE_DEFAULTS]
     *                     from the current preferences, or {@code null} for IDE defaults
     * @param customName   the name of the active custom scheme if one is selected, or
     *                     {@code null} if a built-in scheme is active
     */
    fun setCurrentScheme(codeStyleId: String?, customName: String? = null) {
        isLoading = true
        try {
            val entry: SchemeEntry = when {
                customName != null -> CustomSchemeEntry(customName)
                else -> BuiltInStyle.entries
                    .firstOrNull { it.codeStyleId == codeStyleId }
                    ?.let { BuiltInSchemeEntry(it) }
                    ?: BuiltInSchemeEntry(BuiltInStyle.OFFICIAL)
            }
            combo.selectedItem = entry
            currentScheme = entry
        } finally {
            isLoading = false
        }
    }

    /** Returns the entry currently shown in the combo, or {@code null} if none. */
    fun currentEntry(): SchemeEntry? = combo.selectedItem as? SchemeEntry

    // -------------------------------------------------------------------------
    // Scheme application
    // -------------------------------------------------------------------------

    private fun applySelected(entry: SchemeEntry) {
        combo.hidePopup()
        when (entry) {
            is CustomSchemeEntry -> {
                val settings = KotlinSchemeManager.loadScheme(entry.name)
                    ?: currentSettingsProvider()
                currentScheme = entry
                onStyleApplied(settings)
            }
            is BuiltInSchemeEntry -> applyBuiltIn(entry.style, currentSettingsProvider)
            is SeparatorEntry    -> { /* cannot be selected */ }
        }
    }

    private fun applyBuiltIn(style: BuiltInStyle, settingsProvider: () -> CodeStyleSettings) {
        val settings = when (style) {
            BuiltInStyle.DEFAULTS -> CodeStyleSettings()
            else -> settingsProvider()
        }
        when (style) {
            BuiltInStyle.OFFICIAL -> KotlinOfficialStyleGuide.apply(settings)
            BuiltInStyle.OBSOLETE -> KotlinObsoleteStyleGuide.apply(settings)
            BuiltInStyle.DEFAULTS -> {}
        }
        onStyleApplied(settings)
    }

    // -------------------------------------------------------------------------
    // Gear button popup menu
    // -------------------------------------------------------------------------

    private fun showGearMenu(anchor: Component) {
        val selected = combo.selectedItem as? SchemeEntry ?: return
        val isCustom = selected is CustomSchemeEntry

        val menu = JPopupMenu()

        menu.add("Duplicate").addActionListener { doDuplicate() }
        if (isCustom) {
            menu.add("Rename").addActionListener { doRename(selected as CustomSchemeEntry) }
            menu.add("Delete").addActionListener { doDelete(selected as CustomSchemeEntry) }
        }
        menu.add("Restore Defaults").addActionListener { doRestoreDefaults(selected) }
        menu.addSeparator()
        menu.add("Export…").addActionListener { doExport(selected) }
        menu.add("Import…").addActionListener { doImport() }

        menu.show(anchor, 0, anchor.height)
    }

    // -------------------------------------------------------------------------
    // Gear menu actions
    // -------------------------------------------------------------------------

    private fun doDuplicate() {
        val rawName = JOptionPane.showInputDialog(
            this, "New scheme name:", "Duplicate Scheme", JOptionPane.PLAIN_MESSAGE
        )?.trim() ?: return
        if (rawName.isBlank()) return
        val settings = currentSettingsProvider()
        val baseId = when (val s = combo.selectedItem as? SchemeEntry) {
            is CustomSchemeEntry  -> KotlinSchemeManager.getBasePresetId(s.name)
            is BuiltInSchemeEntry -> s.style.codeStyleId
            else -> null
        }
        KotlinSchemeManager.createScheme(rawName, settings, baseId)
        rebuildCombo(CustomSchemeEntry(rawName))
        currentScheme = CustomSchemeEntry(rawName)
        onSchemeListChanged(rawName)
        applySelected(CustomSchemeEntry(rawName))
    }

    private fun doRename(entry: CustomSchemeEntry) {
        val rawName = JOptionPane.showInputDialog(
            this, "New name:", "Rename Scheme", JOptionPane.PLAIN_MESSAGE,
            null, null, entry.name
        )?.toString()?.trim() ?: return
        if (rawName.isBlank() || rawName == entry.name) return
        KotlinSchemeManager.renameScheme(entry.name, rawName)
        rebuildCombo(CustomSchemeEntry(rawName))
        currentScheme = CustomSchemeEntry(rawName)
        onSchemeListChanged(rawName)
    }

    private fun doDelete(entry: CustomSchemeEntry) {
        val confirm = JOptionPane.showConfirmDialog(
            this,
            "Delete scheme \"${entry.name}\"?",
            "Delete Scheme",
            JOptionPane.YES_NO_OPTION
        )
        if (confirm != JOptionPane.YES_OPTION) return
        KotlinSchemeManager.deleteScheme(entry.name)
        rebuildCombo(BuiltInSchemeEntry(BuiltInStyle.OFFICIAL))
        currentScheme = BuiltInSchemeEntry(BuiltInStyle.OFFICIAL)
        onSchemeListChanged(null)
        applySelected(BuiltInSchemeEntry(BuiltInStyle.OFFICIAL))
    }

    private fun doRestoreDefaults(entry: SchemeEntry) {
        when (entry) {
            is CustomSchemeEntry -> {
                val baseId = KotlinSchemeManager.getBasePresetId(entry.name)
                val style = BuiltInStyle.entries.firstOrNull { it.codeStyleId == baseId }
                    ?: BuiltInStyle.OFFICIAL
                applyBuiltIn(style, currentSettingsProvider)
            }
            is BuiltInSchemeEntry -> applyBuiltIn(entry.style, currentSettingsProvider)
            is SeparatorEntry    -> {}
        }
    }

    private fun doExport(entry: SchemeEntry) {
        val schemeName = when (entry) {
            is CustomSchemeEntry  -> entry.name
            is BuiltInSchemeEntry -> entry.style.title
            is SeparatorEntry    -> return
        }
        val chooser = JFileChooser().apply {
            dialogTitle = "Export Scheme"
            fileFilter = FileNameExtensionFilter("XML files (*.xml)", "xml")
            selectedFile = File(schemeName.replace(Regex("[^\\w\\s-]"), "_") + ".xml")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = ensureXmlExtension(chooser.selectedFile)
        val settings = currentSettingsProvider()
        try {
            KotlinSchemeManager.exportToXml(schemeName, settings, file)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Export failed: ${e.message}", "Export Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun doImport() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Import Scheme"
            fileFilter = FileNameExtensionFilter("XML files (*.xml)", "xml")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val result = KotlinSchemeManager.importFromXml(chooser.selectedFile)
        if (result == null) {
            JOptionPane.showMessageDialog(this, "Failed to import scheme.", "Import Error", JOptionPane.ERROR_MESSAGE)
            return
        }
        var (importedName, settings) = result
        val confirmedName = JOptionPane.showInputDialog(
            this, "Scheme name:", "Import Scheme", JOptionPane.PLAIN_MESSAGE,
            null, null, importedName
        )?.toString()?.trim() ?: return
        if (confirmedName.isBlank()) return
        importedName = confirmedName

        val baseId = settings.kotlinCustomSettings.CODE_STYLE_DEFAULTS
        KotlinSchemeManager.createScheme(importedName, settings, baseId)
        rebuildCombo(CustomSchemeEntry(importedName))
        currentScheme = CustomSchemeEntry(importedName)
        onSchemeListChanged(importedName)
        applySelected(CustomSchemeEntry(importedName))
    }

    private fun ensureXmlExtension(file: File): File =
        if (file.name.lowercase().endsWith(".xml")) file else File(file.parentFile, file.name + ".xml")

    // -------------------------------------------------------------------------
    // Renderer
    // -------------------------------------------------------------------------

    private class SchemeEntryRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            if (value is SeparatorEntry) return JSeparator()
            val label = when (value) {
                is CustomSchemeEntry  -> value.name
                is BuiltInSchemeEntry -> value.style.title
                else                  -> value?.toString() ?: ""
            }
            return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
        }
    }
}
