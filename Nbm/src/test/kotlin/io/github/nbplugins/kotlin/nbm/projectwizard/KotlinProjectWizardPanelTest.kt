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
import java.nio.file.Path
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
}
