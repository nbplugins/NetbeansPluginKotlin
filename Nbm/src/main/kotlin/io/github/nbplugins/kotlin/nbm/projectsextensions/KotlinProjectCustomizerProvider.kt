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
package io.github.nbplugins.kotlin.nbm.projectsextensions

import io.github.nbplugins.kotlin.nbm.options.KotlinProjectOptionsPanelController
import org.netbeans.api.project.Project
import org.netbeans.spi.project.ui.support.ProjectCustomizer
import org.openide.util.Lookup
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Registers the "Kotlin Formatter" category in the Project Properties dialog
 * for Maven, J2SE/Ant, and Gradle projects.
 *
 * <p>Implements [ProjectCustomizer.CompositeCategoryProvider] and is registered in
 * {@code layer.xml} under each project type's {@code Customizer} folder. When the
 * user opens Project Properties and selects "Kotlin Formatter", this provider creates
 * a [KotlinProjectOptionsPanelController] backed by the project's cached settings,
 * embeds its panel in the dialog, and wires the OK action to [KotlinProjectOptionsPanelController.applyChanges].
 *
 * <p>The panel layout — scheme bar, gear menu, 7 tabs, live preview — is identical
 * to Tools&nbsp;&rarr;&nbsp;Options&nbsp;&rarr;&nbsp;Kotlin. Per-project settings are
 * stored lazily in {@code .idea/codeStyles/Project.xml} only when the panel state
 * differs from the global IDE settings.
 */
class KotlinProjectCustomizerProvider : ProjectCustomizer.CompositeCategoryProvider {

    /**
     * Creates the "Kotlin Formatter" category node that appears in the left-hand
     * tree of the Project Properties dialog.
     *
     * @param context the project lookup (unused; project is resolved in [createPanel])
     * @return the category node for "Kotlin Formatter"
     */
    override fun createCategory(context: Lookup): ProjectCustomizer.Category =
        ProjectCustomizer.Category.create(
            "KotlinFormatter",
            "Kotlin Formatter",
            null
        )

    /**
     * Creates the settings panel for the "Kotlin Formatter" category.
     *
     * <p>Resolves the [Project] from [context], constructs a
     * [KotlinProjectOptionsPanelController], loads the project's current settings
     * into the panel, and registers an OK listener that persists the changes.
     *
     * @param category the category whose OK listener is wired to [KotlinProjectOptionsPanelController.applyChanges]
     * @param context  the project lookup; must contain a [Project] instance
     * @return the component to show in the Project Properties dialog
     */
    override fun createComponent(
        category: ProjectCustomizer.Category,
        context: Lookup
    ): JComponent {
        val project = context.lookup(Project::class.java)
            ?: return JPanel()
        val controller = KotlinProjectOptionsPanelController(project)
        controller.load()
        category.setOkButtonListener { controller.applyChanges() }
        return controller.component
    }
}
