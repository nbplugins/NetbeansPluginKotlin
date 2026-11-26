/*******************************************************************************
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
package io.github.nbplugins.kotlin.nbm.projectwizard

import org.jetbrains.kotlin.tools.projectWizard.plugins.buildSystem.BuildSystemType
import org.openide.WizardDescriptor
import org.openide.util.ChangeSupport
import org.openide.util.HelpCtx
import org.openide.util.NbBundle
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField
import javax.swing.event.ChangeListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The single panel of the New Project → Kotlin wizard.
 *
 * Pure view over [KotlinProjectFormModel]: edits are pushed into the model on every
 * keystroke, and the panel only reports validity and stores the resulting
 * [KotlinProjectRequest] into the wizard descriptor, where [KotlinProjectWizardIterator] reads
 * it when the user finishes the wizard.
 *
 * @param variant Kotlin project kind this wizard creates.
 */
class KotlinProjectWizardPanel(private val variant: ProjectVariant) : WizardDescriptor.Panel<WizardDescriptor> {

    private val model = KotlinProjectFormModel(variant)
    private val changeSupport = ChangeSupport(this)
    private var component: JPanel? = null
    private var updatingFields = false

    private var nameField: JTextField? = null
    private var locationField: JTextField? = null
    private var groupIdField: JTextField? = null
    private var artifactIdField: JTextField? = null
    private var versionField: JTextField? = null
    private var buildSystemButtons: Map<BuildSystemType, JRadioButton> = emptyMap()

    /**
     * Builds the swing component on first use.
     *
     * @return the wizard panel to display.
     */
    override fun getComponent(): JPanel {
        var result = component
        if (result == null) {
            result = createComponent()
            component = result
            loadFromModel()
            updateMessages(null)
        }
        return result
    }

    /** There is no dedicated help topic for the Kotlin project wizard. */
    override fun getHelp(): HelpCtx = HelpCtx.DEFAULT_HELP

    /** @return `true` when the entered values allow generating a project. */
    override fun isValid(): Boolean = model.isValid()

    /**
     * Adds a listener notified whenever the entered values — and hence [isValid] — change.
     *
     * @param listener listener to add
     */
    override fun addChangeListener(listener: ChangeListener) {
        changeSupport.addChangeListener(listener)
    }

    /**
     * Removes a previously added change listener.
     *
     * @param listener listener to remove
     */
    override fun removeChangeListener(listener: ChangeListener) {
        changeSupport.removeChangeListener(listener)
    }

    /**
     * Restores the panel state from the wizard, so that returning to this panel keeps the
     * previously entered values.
     *
     * @param wizard wizard descriptor holding a [KotlinProjectRequest] under [KEY_REQUEST]
     * after the first visit
     */
    override fun readSettings(wizard: WizardDescriptor) {
        val request = wizard.getProperty(KEY_REQUEST) as? KotlinProjectRequest
        if (request != null && request.variant == variant) {
            model.projectName = request.directory.fileName?.toString() ?: request.name
            model.location = request.directory.parent?.toString() ?: model.location
            model.groupId = request.groupId
            model.artifactId = request.artifactId
            model.version = request.version
            model.buildSystem = request.buildSystem
            loadFromModel()
        }
        updateMessages(wizard)
    }

    /**
     * Publishes the entered values as a [KotlinProjectRequest] and the current validation
     * feedback to the wizard.
     *
     * @param wizard wizard descriptor the request is stored in
     */
    override fun storeSettings(wizard: WizardDescriptor) {
        wizard.putProperty(KEY_REQUEST, model.toRequest())
        updateMessages(wizard)
    }

    /**
     * Writes the validation state to the wizard so the dialog shows the error (Finish
     * disabled) or the warning (Finish enabled) message.
     *
     * @param wizard wizard descriptor to update, or `null` when no descriptor is available yet
     */
    private fun updateMessages(wizard: WizardDescriptor?) {
        if (wizard == null) return
        val validation = model.validation()
        wizard.putProperty(WizardDescriptor.PROP_ERROR_MESSAGE, validation.errors.firstOrNull()?.let(::messageFor))
        wizard.putProperty(WizardDescriptor.PROP_WARNING_MESSAGE, validation.warnings.firstOrNull()?.let(::messageFor))
    }

    /**
     * @param problem problem reported by [KotlinProjectFormModel.validation]
     * @return localized explanation of [problem]
     */
    private fun messageFor(problem: KotlinProjectProblem): String =
        NbBundle.getMessage(KotlinProjectWizardPanel::class.java, "KotlinProjectWizardPanel.$problem")

    /**
     * Creates the swing component and wires every editor to the model.
     *
     * @return the created component, not yet populated from the model
     */
    private fun createComponent(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.name = NbBundle.getMessage(KotlinProjectWizardPanel::class.java, "KotlinProjectWizardPanel.step")

        var row = 0
        row = addLabeledField(panel, row, "KotlinProjectWizardPanel.projectName") { nameField = it }
        row = addLocationRow(panel, row)
        row = addSectionTitle(panel, row, "KotlinProjectWizardPanel.coordinates")
        row = addLabeledField(panel, row, "KotlinProjectWizardPanel.groupId") { groupIdField = it }
        row = addLabeledField(panel, row, "KotlinProjectWizardPanel.artifactId") { artifactIdField = it }
        row = addLabeledField(panel, row, "KotlinProjectWizardPanel.version") { versionField = it }
        row = addBuildSystemRow(panel, row)
        addInfoLabel(panel, row)
        return panel
    }

    /**
     * Adds a label and a text field to [panel].
     *
     * @param panel panel receiving the row
     * @param row grid row to fill
     * @param bundleKey bundle key of both the label text and the field's accessible name
     * @param fieldSink receives the created text field
     * @return the next free grid row
     */
    private fun addLabeledField(
        panel: JPanel,
        row: Int,
        bundleKey: String,
        fieldSink: (JTextField) -> Unit,
    ): Int {
        val label = JLabel(displayName(bundleKey))
        val field = JTextField(24)
        label.labelFor = field
        field.accessibleContext.accessibleName = displayName(bundleKey)
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = onFieldEdited()

            override fun removeUpdate(event: DocumentEvent) = onFieldEdited()

            override fun changedUpdate(event: DocumentEvent) = onFieldEdited()
        })
        fieldSink(field)
        panel.add(label, constraints(0, row).apply { anchor = GridBagConstraints.EAST })
        panel.add(field, constraints(1, row).apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        })
        return row + 1
    }

    /**
     * Adds the project location field with its Browse button.
     *
     * @param panel panel receiving the row
     * @param row grid row to fill
     * @return the next free grid row
     */
    private fun addLocationRow(panel: JPanel, row: Int): Int {
        val label = JLabel(displayName("KotlinProjectWizardPanel.projectLocation"))
        val field = JTextField(24)
        label.labelFor = field
        field.accessibleContext.accessibleName = displayName("KotlinProjectWizardPanel.projectLocation")
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = onFieldEdited()

            override fun removeUpdate(event: DocumentEvent) = onFieldEdited()

            override fun changedUpdate(event: DocumentEvent) = onFieldEdited()
        })
        locationField = field

        val browse = JButton(displayName("KotlinProjectWizardPanel.browse"))
        browse.accessibleContext.accessibleName = displayName("KotlinProjectWizardPanel.browse")
        browse.addActionListener { chooseLocation(field) }

        panel.add(label, constraints(0, row).apply { anchor = GridBagConstraints.EAST })
        panel.add(field, constraints(1, row).apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        })
        panel.add(browse, constraints(2, row))
        return row + 1
    }

    /**
     * Adds a bold section title spanning the whole row.
     *
     * @param panel panel receiving the row
     * @param row grid row to fill
     * @param bundleKey bundle key of the title text
     * @return the next free grid row
     */
    private fun addSectionTitle(panel: JPanel, row: Int, bundleKey: String): Int {
        val label = JLabel(displayName(bundleKey))
        label.font = label.font.deriveFont(java.awt.Font.BOLD)
        panel.add(label, constraints(0, row).apply {
            gridwidth = 3
            anchor = GridBagConstraints.WEST
            insets = Insets(12, 6, 4, 6)
        })
        return row + 1
    }

    /**
     * Adds the build-system radio group.
     *
     * @param panel panel receiving the row
     * @param row grid row to fill
     * @return the next free grid row
     */
    private fun addBuildSystemRow(panel: JPanel, row: Int): Int {
        val label = JLabel(displayName("KotlinProjectWizardPanel.buildSystem"))
        val group = ButtonGroup()
        val radioPanel = JPanel()
        radioPanel.border = BorderFactory.createEmptyBorder()
        val buttons = linkedMapOf<BuildSystemType, JRadioButton>()
        for (buildSystem in listOf(
            BuildSystemType.GradleKotlinDsl,
            BuildSystemType.GradleGroovyDsl,
            BuildSystemType.Maven,
        )) {
            val button = JRadioButton(buildSystem.text)
            button.accessibleContext.accessibleName = buildSystem.text
            button.addActionListener { onBuildSystemSelected() }
            group.add(button)
            radioPanel.add(button)
            buttons[buildSystem] = button
        }
        buildSystemButtons = buttons
        label.labelFor = buttons.values.first()

        panel.add(label, constraints(0, row).apply { anchor = GridBagConstraints.NORTHWEST })
        panel.add(radioPanel, constraints(1, row).apply {
            gridwidth = 2
            anchor = GridBagConstraints.WEST
        })
        return row + 1
    }

    /**
     * Adds the read-only hint that names the Kotlin version written into build files.
     *
     * @param panel panel receiving the row
     * @param row grid row to fill
     */
    private fun addInfoLabel(panel: JPanel, row: Int) {
        val label = JLabel(NbBundle.getMessage(
            KotlinProjectWizardPanel::class.java, "KotlinProjectWizardPanel.kotlinVersion", GENERATED_KOTLIN_VERSION
        ))
        panel.add(label, constraints(0, row).apply {
            gridwidth = 3
            anchor = GridBagConstraints.WEST
            insets = Insets(12, 6, 6, 6)
        })
        // Keep the fields left-aligned and the dialog from stretching them vertically.
        panel.add(JPanel(), constraints(1, row + 1).apply { weighty = 1.0 })
    }

    /**
     * Creates grid bag constraints for the given cell.
     *
     * @param x grid column
     * @param y grid row
     * @return constraints with the standard insets
     */
    private fun constraints(x: Int, y: Int) = GridBagConstraints().apply {
        gridx = x
        gridy = y
        insets = Insets(4, 6, 4, 6)
        anchor = GridBagConstraints.WEST
    }

    /**
     * Opens a directory chooser and writes the selection into [field].
     *
     * @param field project location field to update
     */
    private fun chooseLocation(field: JTextField) {
        val chooser = JFileChooser(field.text.ifBlank { null })
        chooser.dialogTitle = displayName("KotlinProjectWizardPanel.chooseLocation")
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isMultiSelectionEnabled = false
        if (chooser.showOpenDialog(component ?: return) == JFileChooser.APPROVE_OPTION) {
            field.text = chooser.selectedFile?.absolutePath ?: field.text
        }
    }

    /**
     * Copies the current field values into the model and notifies listeners.
     */
    private fun onFieldEdited() {
        if (updatingFields) return
        nameField?.let { model.projectName = it.text.trim() }
        locationField?.let { model.location = it.text.trim() }
        groupIdField?.let { model.groupId = it.text.trim() }
        artifactIdField?.let { model.artifactId = it.text.trim() }
        versionField?.let { model.version = it.text.trim() }
        // The model may have re-derived the artifact ID from the project name.
        updatingFields = true
        try {
            artifactIdField?.let {
                if (it.text != model.artifactId) it.text = model.artifactId
            }
        } finally {
            updatingFields = false
        }
        changeSupport.fireChange()
    }

    /**
     * Copies the selected build system into the model and notifies listeners.
     */
    private fun onBuildSystemSelected() {
        buildSystemButtons.entries.firstOrNull { it.value.isSelected }?.let { model.buildSystem = it.key }
        changeSupport.fireChange()
    }

    /**
     * Fills every editor from the model without generating edit events.
     */
    private fun loadFromModel() {
        updatingFields = true
        try {
            nameField?.text = model.projectName
            locationField?.text = model.location
            groupIdField?.text = model.groupId
            artifactIdField?.text = model.artifactId
            versionField?.text = model.version
            buildSystemButtons[model.buildSystem]?.isSelected = true
        } finally {
            updatingFields = false
        }
    }

    /**
     * @param bundleKey key in the module bundle
     * @return localized text for [bundleKey]
     */
    private fun displayName(bundleKey: String): String =
        NbBundle.getMessage(KotlinProjectWizardPanel::class.java, bundleKey)

    companion object {
        /**
         * Wizard property holding the [KotlinProjectRequest] produced by the panel.
         */
        const val KEY_REQUEST: String = "io.github.nbplugins.kotlin.projectwizard.request"
    }
}
