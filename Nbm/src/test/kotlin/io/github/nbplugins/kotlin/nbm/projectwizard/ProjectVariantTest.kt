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
import org.jetbrains.kotlin.tools.projectWizard.settings.buildsystem.Module
import org.netbeans.junit.NbTestCase

/**
 * Tests for [ProjectVariant].
 */
class ProjectVariantTest : NbTestCase("ProjectVariantTest") {

    /** Only SCRIPT contains a generated script, and it lives under `src/main/kotlin`. */
    fun testScriptRelativePath() {
        assertEquals("src/main/kotlin/main.kts", ProjectVariant.SCRIPT.scriptRelativePath)
        assertNull(ProjectVariant.APPLICATION.scriptRelativePath)
        assertNull(ProjectVariant.LIBRARY.scriptRelativePath)
    }

    /** The application variant uses the local template that adds the console sample. */
    fun testApplicationTemplate() {
        val template = ProjectVariant.APPLICATION.template("demo")

        assertTrue(template is KotlinApplicationProjectTemplate)
        assertEquals("demo", template.suggestedProjectName.removePrefix("my").replaceFirstChar { it.lowercase() })
    }

    /** Library and script share the local template without sample code. */
    fun testLibraryAndScriptTemplates() {
        assertTrue(ProjectVariant.LIBRARY.template("demo") is KotlinLibraryProjectTemplate)
        assertTrue(ProjectVariant.SCRIPT.template("demo") is KotlinLibraryProjectTemplate)
    }

    /** Every variant names its single module after the given project. */
    fun testTemplateModuleName() {
        for (variant in ProjectVariant.values()) {
            val template = variant.template("demo")
            val modulesSetting = template.setsValues.firstOrNull {
                it.setting == KotlinPlugin.modules.reference
            }
            assertNotNull("$variant must configure the modules setting", modulesSetting)
            @Suppress("UNCHECKED_CAST")
            val modules = modulesSetting!!.value as List<Module>
            assertEquals("$variant module name", "demo", modules.single().name)
        }
    }
}
