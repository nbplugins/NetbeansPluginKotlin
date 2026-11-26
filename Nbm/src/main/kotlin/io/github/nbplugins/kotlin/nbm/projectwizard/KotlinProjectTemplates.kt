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

import org.jetbrains.kotlin.tools.projectWizard.core.entity.settings.reference
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.KotlinPlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.kotlin.ProjectKind
import org.jetbrains.kotlin.tools.projectWizard.projectTemplates.ProjectTemplate
import org.jetbrains.kotlin.tools.projectWizard.projectTemplates.SettingWithValue
import org.jetbrains.kotlin.tools.projectWizard.projectTemplates.createDefaultSourceSets
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.Module
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.SinglePlatformModule
import org.jetbrains.kotlin.tools.projectWizard.templates.ConsoleJvmApplicationTemplate

/**
 * Base of the Kotlin project templates used by the NetBeans New Project wizard.
 *
 * The single module of a generated project is named after the artifact ID entered in the wizard.
 * The name matters because the upstream Maven writer emits it as the root `<name>` element, and
 * NetBeans displays a Maven project by that element: with the upstream templates the project
 * would be shown as `consoleApp` or `lib` instead of the name chosen in the dialog. The upstream
 * built-in templates hard-code their module names, so both variants are assembled from the same
 * upstream pieces locally.
 *
 * @param moduleName name of the single module, normally the artifact ID
 */
sealed class KotlinProjectTemplate(protected val moduleName: String) : ProjectTemplate() {

    override val title: String get() = id

    override val description: String get() = "A $title project"

    override val suggestedProjectName: String get() = "my${moduleName.replaceFirstChar { it.uppercase() }}"

    override val projectKind: ProjectKind get() = ProjectKind.Singleplatform

    /**
     * The single JVM module with the default source sets.
     *
     * @param permittedTemplateIds when set, restricts the module templates that may be attached
     * @return a fresh module, not yet attached to the template
     */
    protected fun module(permittedTemplateIds: Set<String>? = null): Module =
        SinglePlatformModule(moduleName, createDefaultSourceSets(), permittedTemplateIds)

    /**
     * The single JVM module with the default source sets.
     *
     * Built once because the module is handed to the settings writer, which records the
     * defaults on it: a new instance on every access would lose those defaults.
     */
    private val defaultModule: Module by lazy { module() }

    /**
     * The list of settings applied when the template is selected.
     */
    override val setsPluginSettings: List<SettingWithValue<*, *>>
        get() = listOf(SettingWithValue(KotlinPlugin.modules.reference, listOf(defaultModule)))
}

/**
 * A Kotlin/JVM console application with the upstream `Main.kt` sample.
 *
 * Mirrors `ConsoleApplicationProjectTemplateWithSample`, except that the module is named after
 * the project instead of `consoleApp`.
 *
 * @param moduleName name of the single module, normally the artifact ID
 */
class KotlinApplicationProjectTemplate(moduleName: String) : KotlinProjectTemplate(moduleName) {

    override val id: String = "kotlinApplication"

    /**
     * The module with the console sample attached. Built once because `withTemplate` records the
     * template settings on the template instance.
     */
    private val applicationModule: Module by lazy {
        module(permittedTemplateIds = setOf(ConsoleJvmApplicationTemplate.id)).apply {
            withTemplate(ConsoleJvmApplicationTemplate)
        }
    }

    override val setsPluginSettings: List<SettingWithValue<*, *>>
        get() = listOf(SettingWithValue(KotlinPlugin.modules.reference, listOf(applicationModule)))
}

/**
 * A Kotlin/JVM library: the standard source sets and no sample code.
 *
 * Replaces the upstream console-application template for the Library and Script variants, which
 * must not get a `Main.kt`.
 *
 * @param moduleName name of the single module, normally the artifact ID
 */
class KotlinLibraryProjectTemplate(moduleName: String) : KotlinProjectTemplate(moduleName) {

    override val id: String = "kotlinLibrary"
}
