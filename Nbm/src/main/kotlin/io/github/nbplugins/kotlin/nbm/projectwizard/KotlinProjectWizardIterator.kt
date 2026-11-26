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

import org.netbeans.api.progress.ProgressHandle
import org.netbeans.api.progress.ProgressHandleFactory
import org.openide.WizardDescriptor
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import java.io.IOException
import java.util.NoSuchElementException
import javax.swing.JComponent
import javax.swing.event.ChangeListener

/**
 * Controller of the New Project → Kotlin wizard: wires [KotlinProjectWizardPanel] to
 * [KotlinProjectGenerator] and hands the generated project directory back to the New Project
 * action.
 *
 * Registered in `layer.xml` under `Templates/Project/Kotlin`, one concrete subclass per
 * [ProjectVariant] because the layer instantiates iterators through their no-argument
 * constructor. The returned `DataObject` is the project directory itself: the New Project
 * action resolves it to a `Project` with `ProjectManager.findProject` and opens it, exactly as
 * for the standard NetBeans project templates.
 *
 * @param variant Kotlin project kind created by this iterator.
 */
open class KotlinProjectWizardIterator protected constructor(
    private val variant: ProjectVariant,
) : WizardDescriptor.ProgressInstantiatingIterator<WizardDescriptor> {

    private var index = 0
    private var wizard: WizardDescriptor? = null
    private var panels: List<WizardDescriptor.Panel<WizardDescriptor>>? = null

    /**
     * Returns the wizard panels, creating them on first use.
     *
     * @return the single name-and-location panel, decorated with the wizard content data the
     * New Project dialog prepends its template chooser step to
     */
    protected fun getPanels(): List<WizardDescriptor.Panel<WizardDescriptor>> {
        var result = panels
        if (result == null) {
            result = listOf(KotlinProjectWizardPanel(variant))
            val currentWizard = checkNotNull(wizard) { "initialize() must be called before current()" }
            val steps = createSteps(currentWizard, result)
            for ((panelIndex, panel) in result.withIndex()) {
                val swingComponent = panel.component as? JComponent ?: continue
                swingComponent.putClientProperty(WizardDescriptor.PROP_CONTENT_SELECTED_INDEX, panelIndex)
                swingComponent.putClientProperty(WizardDescriptor.PROP_CONTENT_DATA, steps)
                swingComponent.putClientProperty(WizardDescriptor.PROP_AUTO_WIZARD_STYLE, true)
                swingComponent.putClientProperty(WizardDescriptor.PROP_CONTENT_DISPLAYED, true)
                swingComponent.putClientProperty(WizardDescriptor.PROP_CONTENT_NUMBERED, true)
            }
            panels = result
        }
        return result
    }

    /**
     * Computes the step titles shown at the top of the New Project dialog.
     *
     * @param wizard wizard descriptor holding the template-chooser steps
     * @param panels panels of this iterator
     * @return the template-chooser steps followed by this iterator's step titles
     */
    private fun createSteps(
        wizard: WizardDescriptor,
        panels: List<WizardDescriptor.Panel<WizardDescriptor>>,
    ): Array<String> {
        @Suppress("UNCHECKED_CAST")
        val beforeSteps = (wizard.getProperty("WizardPanel_contentData") as? Array<String>) ?: emptyArray()
        // The last template-chooser entry is the selected template itself; the iterator
        // replaces it with its own panel titles.
        val preserved = if (beforeSteps.isEmpty()) {
            emptyArray()
        } else {
            beforeSteps.copyOfRange(0, beforeSteps.size - 1)
        }
        return preserved + panels.map { it.component.name.orEmpty() }
    }

    /**
     * Generates the project and returns its directory as a [DataObject] for the New Project
     * action to open.
     *
     * @param progress handle updated with generator messages
     * @return a singleton set with the generated project directory, or an empty set when the
     * worker thread was interrupted (the user cancelled the wizard) before generation finished
     * @throws IOException when the project cannot be generated or its directory cannot be
     * turned into a `DataObject`
     */
    override fun instantiate(progress: ProgressHandle): Set<DataObject> {
        val descriptor = checkNotNull(wizard) { "initialize() must be called before instantiate()" }
        val request = descriptor.getProperty(KotlinProjectWizardPanel.KEY_REQUEST) as? KotlinProjectRequest
            ?: throw IOException("The Kotlin project wizard was not filled in")

        progress.start()
        try {
            progress.switchToIndeterminate()
            KotlinProjectGenerator().generate(
                request,
                onProgress = { message -> progress.progress(message) },
                // ProgressHandle has no cancellation query; the New Project action cancels an
                // instantiation by interrupting its worker thread, which is what we poll here.
                isCancelled = { Thread.currentThread().isInterrupted },
            )
        } catch (cancelled: KotlinProjectGenerationCancelledException) {
            return emptySet()
        } catch (failure: KotlinProjectGenerationException) {
            throw IOException(failure.message ?: "Cannot generate the Kotlin project", failure)
        } finally {
            progress.finish()
        }

        // FileUtil.toFileObject requires a normalized absolute file: the typed location may
        // contain "." or ".." components (for instance "/tmp/.").
        val directory = FileUtil.normalizeFile(request.directory.toFile())
        val projectDirectory = FileUtil.toFileObject(directory)
            ?: throw IOException("Cannot find the generated project directory $directory")
        projectDirectory.refresh()
        return setOf(DataObject.find(projectDirectory))
    }

    /**
     * Non-progress variant required by [WizardDescriptor.InstantiatingIterator].
     *
     * The New Project dialog always calls the progress variant; this one exists so that
     * programmatic instantiation through the templates API also works.
     *
     * @return the same result as [instantiate] with a private progress handle
     */
    override fun instantiate(): Set<DataObject> =
        instantiate(ProgressHandleFactory.createHandle("Generating Kotlin project"))

    /**
     * Remembers the wizard descriptor so the panel can store the project request in it.
     *
     * @param wizard descriptor created by the New Project dialog
     */
    override fun initialize(wizard: WizardDescriptor) {
        this.wizard = wizard
    }

    /**
     * Releases the panels and the wizard reference.
     *
     * @param wizard descriptor that is no longer used
     */
    override fun uninitialize(wizard: WizardDescriptor) {
        this.wizard = null
        panels = null
        index = 0
    }

    /** @return the currently displayed panel. */
    override fun current(): WizardDescriptor.Panel<WizardDescriptor> = getPanels()[index]

    /** @return the step label of the current panel. */
    override fun name(): String = "${index + 1}. ${current().component.name}"

    /** @return `true` while another panel follows the current one. */
    override fun hasNext(): Boolean = index < getPanels().size - 1

    /** @return `true` when a previous panel exists. */
    override fun hasPrevious(): Boolean = index > 0

    /** Advances to the next panel. */
    override fun nextPanel() {
        if (!hasNext()) throw NoSuchElementException()
        index++
    }

    /** Returns to the previous panel. */
    override fun previousPanel() {
        if (!hasPrevious()) throw NoSuchElementException()
        index--
    }

    /**
     * Change listeners are delegated to the current panel through the wizard descriptor, so
     * the iterator itself has nothing to notify.
     *
     * @param listener listener to add
     */
    override fun addChangeListener(listener: ChangeListener) = Unit

    /**
     * @param listener listener to remove
     */
    override fun removeChangeListener(listener: ChangeListener) = Unit
}

/**
 * Creates a **Kotlin Application** project (Kotlin/JVM console application with a sample
 * `Main.kt`).
 */
class KotlinApplicationWizardIterator : KotlinProjectWizardIterator(ProjectVariant.APPLICATION)

/**
 * Creates a **Kotlin Library** project (Kotlin/JVM library without sample code).
 */
class KotlinLibraryWizardIterator : KotlinProjectWizardIterator(ProjectVariant.LIBRARY)

/**
 * Creates a **Kotlin Script** project (a Kotlin/JVM project with a `main.kts` script).
 */
class KotlinScriptProjectWizardIterator : KotlinProjectWizardIterator(ProjectVariant.SCRIPT)
