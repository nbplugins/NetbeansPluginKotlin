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
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.Module
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.SourcesetType
import org.netbeans.junit.NbTestCase

/**
 * Tests for [KotlinLibraryProjectTemplate].
 */
class KotlinLibraryProjectTemplateTest : NbTestCase("KotlinLibraryProjectTemplateTest") {

    /** Template identity is used by IDEA code paths that resolve templates by ID. */
    fun testTemplateIdentity() {
        assertEquals("kotlinLibrary", KotlinLibraryProjectTemplate.id)
        assertEquals("Kotlin Library", KotlinLibraryProjectTemplate.title)
        assertEquals("myKotlinLibrary", KotlinLibraryProjectTemplate.suggestedProjectName)
        assertEquals(ProjectKind.Singleplatform, KotlinLibraryProjectTemplate.projectKind)
        assertTrue(KotlinLibraryProjectTemplate.description.isNotBlank())
    }

    /** The template configures exactly one `lib` module with both default source sets. */
    fun testTemplateSetsSingleLibModule() {
        val settings = KotlinLibraryProjectTemplate.setsValues
        val modulesSetting = settings.firstOrNull { it.setting == KotlinPlugin.modules.reference }
        assertNotNull("modules setting must be present", modulesSetting)
        @Suppress("UNCHECKED_CAST")
        val modules = modulesSetting!!.value as List<Module>
        assertEquals(1, modules.size)
        val module = modules.single()
        assertEquals("lib", module.name)
        assertNull("library module must not have a sample template", module.template)
        assertEquals(
            SourcesetType.values().toSet(),
            module.sourceSets.map { it.sourcesetType }.toSet()
        )
    }

    /** The template also sets the project kind, as every IDEA project template does. */
    fun testTemplateSetsProjectKind() {
        val kindSetting = KotlinLibraryProjectTemplate.setsValues.firstOrNull {
            it.setting == KotlinPlugin.projectKind.reference
        }
        assertNotNull("project kind setting must be present", kindSetting)
        assertEquals(ProjectKind.Singleplatform, kindSetting!!.value)
    }
}
