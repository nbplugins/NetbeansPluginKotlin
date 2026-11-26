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
import org.netbeans.junit.NbTestCase
import org.openide.WizardDescriptor
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener

/**
 * Tests for [KotlinProjectWizardPanel]: the view exposes the model defaults, stores the
 * request into the wizard descriptor and reports validation feedback.
 */
class KotlinProjectWizardPanelTest : NbTestCase("KotlinProjectWizardPanelTest") {

    /** Each test starts with an empty work directory. */
    override fun setUp() {
        super.setUp()
        clearWorkDir()
    }

    /** The panel shows the model defaults and is valid out of the box. */
    fun testComponentShowsDefaultsAndIsValid() {
        val panel = panel(ProjectVariant.APPLICATION)
        val wizard = wizard(panel)

        panel.readSettings(wizard)
        val component = panel.component

        assertEquals("Name and Location", component.name)
        assertTrue(panel.isValid())
        assertNull(wizard.getProperty(WizardDescriptor.PROP_ERROR_MESSAGE))
        assertNull(wizard.getProperty(WizardDescriptor.PROP_WARNING_MESSAGE))
    }

    /** Finishing the wizard publishes the request with the panel's variant. */
    fun testStoreSettingsPublishesRequest() {
        val panel = panel(ProjectVariant.LIBRARY)
        val wizard = wizard(panel)

        panel.readSettings(wizard)
        panel.storeSettings(wizard)

        val request = wizard.getProperty(KotlinProjectWizardPanel.KEY_REQUEST) as KotlinProjectRequest
        assertEquals(ProjectVariant.LIBRARY, request.variant)
        assertEquals("KotlinLibrary", request.name)
        assertEquals("KotlinLibrary", request.artifactId)
        assertEquals(BuildSystemType.GradleKotlinDsl, request.buildSystem)
        assertEquals(Path.of(KotlinProjectFormModel.defaultProjectsLocation()).resolve("KotlinLibrary"), request.directory)
    }

    /** Re-opening the panel restores the values stored by the previous visit. */
    fun testReadSettingsRestoresStoredRequest() {
        val wizard = WizardDescriptor(arrayOf<WizardDescriptor.Panel<WizardDescriptor>>())
        wizard.putProperty(KotlinProjectWizardPanel.KEY_REQUEST, KotlinProjectRequest(
            directory = Path.of("/tmp/projects/demo"),
            name = "demo",
            groupId = "com.example",
            artifactId = "demo-lib",
            version = "2.0.0",
            buildSystem = BuildSystemType.Maven,
            variant = ProjectVariant.LIBRARY,
        ))

        val panel = panel(ProjectVariant.LIBRARY)
        panel.readSettings(wizard)
        panel.storeSettings(wizard)

        val restored = wizard.getProperty(KotlinProjectWizardPanel.KEY_REQUEST) as KotlinProjectRequest
        assertEquals(Path.of("/tmp/projects/demo"), restored.directory)
        assertEquals("demo", restored.name)
        assertEquals("com.example", restored.groupId)
        assertEquals("demo-lib", restored.artifactId)
        assertEquals("2.0.0", restored.version)
        assertEquals(BuildSystemType.Maven, restored.buildSystem)
    }

    /** An invalid stored project name makes the panel invalid and publishes the error. */
    fun testInvalidNameDisablesFinishAndShowsError() {
        val wizard = WizardDescriptor(arrayOf<WizardDescriptor.Panel<WizardDescriptor>>())
        wizard.putProperty(KotlinProjectWizardPanel.KEY_REQUEST, KotlinProjectRequest(
            directory = Path.of("/tmp/projects/bad|name"),
            name = "bad|name",
            groupId = "org.example",
            artifactId = "bad",
            version = "1.0-SNAPSHOT",
            buildSystem = BuildSystemType.GradleKotlinDsl,
            variant = ProjectVariant.APPLICATION,
        ))

        val panel = panel(ProjectVariant.APPLICATION)
        panel.readSettings(wizard)

        assertFalse(panel.isValid())
        assertEquals(
            "The project name may contain only letters, digits, spaces, \"-\", \"_\" and \".\".",
            wizard.getProperty(WizardDescriptor.PROP_ERROR_MESSAGE),
        )
    }

    /** A non-empty target directory only produces a warning; the panel stays valid. */
    fun testNonEmptyDirectoryProducesWarningOnly() {
        val directory = getWorkDir().toPath().resolve("existing")
        java.nio.file.Files.createDirectories(directory)
        java.nio.file.Files.createFile(directory.resolve("keep.txt"))
        val wizard = WizardDescriptor(arrayOf<WizardDescriptor.Panel<WizardDescriptor>>())
        wizard.putProperty(KotlinProjectWizardPanel.KEY_REQUEST, KotlinProjectRequest(
            directory = directory,
            name = "existing",
            groupId = "org.example",
            artifactId = "existing",
            version = "1.0-SNAPSHOT",
            buildSystem = BuildSystemType.GradleKotlinDsl,
            variant = ProjectVariant.LIBRARY,
        ))

        val panel = panel(ProjectVariant.LIBRARY)
        panel.readSettings(wizard)
        panel.storeSettings(wizard)

        assertTrue(panel.isValid())
        assertNull(wizard.getProperty(WizardDescriptor.PROP_ERROR_MESSAGE))
        assertNotNull(wizard.getProperty(WizardDescriptor.PROP_WARNING_MESSAGE))
    }

    /** Change listeners registered by the wizard can be added and removed. */
    fun testChangeListenersCanBeRegistered() {
        val panel = panel(ProjectVariant.SCRIPT)
        val listener = ChangeListener { }

        panel.addChangeListener(listener)
        panel.removeChangeListener(listener)
    }

    /** Editing the project name keeps the still-derived artifact ID in sync. */
    fun testEditingNameUpdatesDerivedArtifactId() {
        val panel = panel(ProjectVariant.APPLICATION)
        val wizard = wizard(panel)
        panel.readSettings(wizard)
        val fields = textFields(panel)

        editOnEdt { fields[NAME].text = "demo" }

        assertEquals("demo", fields[ARTIFACT].text)
        panel.storeSettings(wizard)
        assertEquals("demo", request(wizard).artifactId)
    }

    /** An artifact ID the user has customized is not overwritten by a project name change. */
    fun testCustomArtifactIdSurvivesNameEdit() {
        val panel = panel(ProjectVariant.APPLICATION)
        val wizard = wizard(panel)
        panel.readSettings(wizard)
        val fields = textFields(panel)

        editOnEdt { fields[ARTIFACT].text = "my-lib" }
        editOnEdt { fields[NAME].text = "demo" }

        assertEquals("my-lib", fields[ARTIFACT].text)
        panel.storeSettings(wizard)
        assertEquals("my-lib", request(wizard).artifactId)
    }

    /** Padding in the artifact ID is trimmed in the model without mutating the notifying document. */
    fun testEditingArtifactIdWithPaddingIsSafe() {
        val panel = panel(ProjectVariant.APPLICATION)
        val wizard = wizard(panel)
        panel.readSettings(wizard)
        val fields = textFields(panel)

        editOnEdt { fields[ARTIFACT].text = "demo " }

        panel.storeSettings(wizard)
        assertEquals("demo", request(wizard).artifactId)
    }

    /** The non-empty directory warning appears as soon as the location points at it. */
    fun testEditingLocationPublishesWarningBeforeFinish() {
        val taken = getWorkDir().toPath().resolve("taken")
        Files.createDirectories(taken)
        Files.createFile(taken.resolve("keep.txt"))
        val panel = panel(ProjectVariant.LIBRARY)
        val wizard = wizard(panel)
        panel.readSettings(wizard)
        val fields = textFields(panel)

        editOnEdt {
            fields[LOCATION].text = taken.parent.toString()
            fields[NAME].text = "taken"
        }

        assertTrue(panel.isValid())
        assertNotNull(
            "the overwrite warning must be published before Finish",
            wizard.getProperty(WizardDescriptor.PROP_WARNING_MESSAGE),
        )
    }

    /** An invalid project name publishes the error as soon as it is typed. */
    fun testEditingInvalidNamePublishesErrorBeforeFinish() {
        val panel = panel(ProjectVariant.APPLICATION)
        val wizard = wizard(panel)
        panel.readSettings(wizard)
        val fields = textFields(panel)

        editOnEdt { fields[NAME].text = "bad|name" }

        assertFalse(panel.isValid())
        assertNotNull(
            "the error must be published before Finish",
            wizard.getProperty(WizardDescriptor.PROP_ERROR_MESSAGE),
        )
    }

    /**
     * Runs [block] on the event dispatch thread, where swing documents are meant to be edited.
     */
    private fun editOnEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    /**
     * @param wizard descriptor holding the stored request
     * @return the request stored by the last [KotlinProjectWizardPanel.storeSettings]
     */
    private fun request(wizard: WizardDescriptor): KotlinProjectRequest =
        wizard.getProperty(KotlinProjectWizardPanel.KEY_REQUEST) as KotlinProjectRequest

    /**
     * Collects the text fields of [panel] in layout order.
     *
     * @return name, location, group ID, artifact ID and version editors, in that order
     */
    private fun textFields(panel: KotlinProjectWizardPanel): List<JTextField> {
        val fields = mutableListOf<JTextField>()

        fun collect(container: Container) {
            for (child in container.components) {
                when (child) {
                    is JTextField -> fields += child
                    is Container -> collect(child)
                }
            }
        }

        collect(panel.component)
        return fields
    }

    /**
     * Creates a panel and materializes its swing component on the event dispatch thread.
     */
    private fun panel(variant: ProjectVariant): KotlinProjectWizardPanel {
        val panel = KotlinProjectWizardPanel(variant)
        SwingUtilities.invokeAndWait { panel.getComponent() }
        return panel
    }

    /**
     * Creates a wizard descriptor whose iterator is the given panel.
     */
    private fun wizard(panel: KotlinProjectWizardPanel): WizardDescriptor =
        WizardDescriptor(arrayOf<WizardDescriptor.Panel<WizardDescriptor>>(panel))

    private companion object {
        /** Index of the project name editor in [textFields]. */
        const val NAME = 0

        /** Index of the project location editor in [textFields]. */
        const val LOCATION = 1

        /** Index of the artifact ID editor in [textFields]. */
        const val ARTIFACT = 3
    }
}
