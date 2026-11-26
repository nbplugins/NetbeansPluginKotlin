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
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.SinglePlatformModule

/**
 * A Kotlin/JVM library project template built from the upstream New Project Wizard pieces.
 *
 * The upstream console-application template names its single module `consoleApp` and always
 * attaches the console sample. A library (or a script project) must instead produce a plain
 * `lib` module with the standard source sets and no sample code, so this template reuses
 * [ProjectTemplate] and the same `KotlinPlugin.modules` setting with a different module.
 */
object KotlinLibraryProjectTemplate : ProjectTemplate() {

    override val title: String = "Kotlin Library"

    override val description: String = "A Kotlin/JVM library project"

    override val id: String = "kotlinLibrary"

    override val suggestedProjectName: String = "myKotlinLibrary"

    override val projectKind: ProjectKind = ProjectKind.Singleplatform

    /**
     * The single `lib` module with default source sets and no template attached.
     */
    override val setsPluginSettings: List<SettingWithValue<*, *>>
        get() = listOf(
            SettingWithValue(
                KotlinPlugin.modules.reference,
                listOf(SinglePlatformModule("lib", createDefaultSourceSets()))
            )
        )
}
