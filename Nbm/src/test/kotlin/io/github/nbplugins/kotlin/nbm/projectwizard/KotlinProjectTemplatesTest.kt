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
import org.jetbrains.kotlin.tools.projectWizard.templates.ConsoleJvmApplicationTemplate
import org.netbeans.junit.NbTestCase

/**
 * Tests for [KotlinLibraryProjectTemplate]: a single module named after the project, with the
 * default source sets and no sample code.
 */
class KotlinLibraryProjectTemplateTest : NbTestCase("KotlinLibraryProjectTemplateTest") {

    private val template = KotlinLibraryProjectTemplate("demo")

    /** Template identity is used by IDEA code paths that resolve templates by ID. */
    fun testTemplateIdentity() {
        assertEquals("kotlinLibrary", template.id)
        assertEquals("myDemo", template.suggestedProjectName)
        assertEquals(ProjectKind.Singleplatform, template.projectKind)
        assertTrue(template.title.isNotBlank())
        assertTrue(template.description.isNotBlank())
    }

    /** The template configures exactly one module, named after the project. */
    fun testTemplateSetsSingleModule() {
        val modules = template.modules()
        assertEquals(1, modules.size)
        val module = modules.single()
        assertEquals("demo", module.name)
        assertNull("library module must not have a sample template", module.template)
        assertEquals(
            SourcesetType.values().toSet(),
            module.sourceSets.map { it.sourcesetType }.toSet()
        )
    }

    /** The template also sets the project kind, as every IDEA project template does. */
    fun testTemplateSetsProjectKind() {
        val kindSetting = template.setsValues.firstOrNull {
            it.setting == KotlinPlugin.projectKind.reference
        }
        assertNotNull("project kind setting must be present", kindSetting)
        assertEquals(ProjectKind.Singleplatform, kindSetting!!.value)
    }

    /** Each template access returns the same module, so repeated application is stable. */
    fun testModuleIsStableAcrossAccesses() {
        val first = template.modules().single()
        val second = template.modules().single()

        assertEquals(first.name, second.name)
        assertSame(first, second)
    }
}

/**
 * Tests for [KotlinApplicationProjectTemplate]: a single module named after the project with
 * the upstream `Main.kt` sample attached.
 */
class KotlinApplicationProjectTemplateTest : NbTestCase("KotlinApplicationProjectTemplateTest") {

    private val template = KotlinApplicationProjectTemplate("demo")

    /** Template identity is used by IDEA code paths that resolve templates by ID. */
    fun testTemplateIdentity() {
        assertEquals("kotlinApplication", template.id)
        assertEquals(ProjectKind.Singleplatform, template.projectKind)
        assertTrue(template.title.isNotBlank())
        assertTrue(template.description.isNotBlank())
    }

    /** The sample template is attached to the project-named module. */
    fun testTemplateAttachesConsoleSample() {
        val module = template.modules().single()

        assertEquals("demo", module.name)
        assertEquals(ConsoleJvmApplicationTemplate, module.template)
        assertEquals(setOf(ConsoleJvmApplicationTemplate.id), module.permittedTemplateIds)
        assertEquals(
            SourcesetType.values().toSet(),
            module.sourceSets.map { it.sourcesetType }.toSet()
        )
    }

    /** The module is built once: `withTemplate` records settings on the template instance. */
    fun testModuleIsStableAcrossAccesses() {
        val first = template.modules().single()
        val second = template.modules().single()

        assertSame(first, second)
    }
}

/**
 * Extracts the modules configured by a template.
 *
 * @return the modules value of the `KotlinPlugin.modules` setting
 */
private fun org.jetbrains.kotlin.tools.projectWizard.projectTemplates.ProjectTemplate.modules(): List<Module> {
    val modulesSetting = checkNotNull(setsValues.firstOrNull { it.setting == KotlinPlugin.modules.reference }) {
        "modules setting must be present"
    }
    @Suppress("UNCHECKED_CAST")
    return modulesSetting.value as List<Module>
}
