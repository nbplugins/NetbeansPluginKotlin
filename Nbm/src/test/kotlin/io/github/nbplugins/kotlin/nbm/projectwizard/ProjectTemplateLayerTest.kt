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

import org.netbeans.junit.NbTestCase
import org.openide.WizardDescriptor
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil

/**
 * Tests the hand-written layer registrations of the New Project → Kotlin category.
 *
 * The entries mirror what `TemplateRegistrationProcessor` would generate for
 * `@TemplateRegistration(folder = "Project/Kotlin", ...)`, so this test guards the file names,
 * attribute names and iterator classes against typos.
 */
class ProjectTemplateLayerTest : NbTestCase("ProjectTemplateLayerTest") {

    /** The Kotlin category exists and contains exactly the three project templates. */
    fun testKotlinProjectCategoryContainsThreeTemplates() {
        val category = FileUtil.getConfigFile("Templates/Project/Kotlin")

        assertNotNull("Templates/Project/Kotlin must be registered in the layer", category)
        assertEquals(
            setOf("KotlinApplicationProject.xml", "KotlinLibraryProject.xml", "KotlinScriptProject.xml"),
            category.children.map { it.nameExt }.toSet(),
        )
    }

    /** Every template points at an existing description and at the iterator for its variant. */
    fun testTemplateAttributes() {
        val expected = mapOf(
            "KotlinApplicationProject.xml" to "KotlinApplicationWizardIterator",
            "KotlinLibraryProject.xml" to "KotlinLibraryWizardIterator",
            "KotlinScriptProject.xml" to "KotlinScriptProjectWizardIterator",
        )
        for ((fileName, iteratorSimpleName) in expected) {
            val template = template(fileName)

            assertEquals(java.lang.Boolean.TRUE, template.getAttribute("template"))
            assertNotNull("$fileName must have a position", template.getAttribute("position"))
            assertTrue("$fileName must have a display name", template.getAttribute("displayName") is String)
            val iterator = template.getAttribute("instantiatingIterator")
            assertTrue(
                "$fileName must instantiate $iteratorSimpleName, was $iterator",
                iterator is WizardDescriptor.InstantiatingIterator<*>,
            )
            assertEquals(iteratorSimpleName, iterator!!.javaClass.simpleName)
            assertNotNull("$fileName must have a description", template.getAttribute("instantiatingWizardURL"))
        }
    }

    /** Each template has a readable description resource and an icon. */
    fun testTemplateResourcesExist() {
        for (fileName in listOf("KotlinApplicationProject", "KotlinLibraryProject", "KotlinScriptProject")) {
            assertNotNull(
                "description $fileName.html must exist",
                KotlinProjectWizardPanel::class.java.getResource("/org/jetbrains/kotlin/project/templates/$fileName.html"),
            )
            assertNotNull(
                "template file $fileName.xml must exist",
                KotlinProjectWizardPanel::class.java.getResource("/org/jetbrains/kotlin/project/templates/$fileName.xml"),
            )
        }
        assertNotNull(
            "template icon must exist",
            KotlinProjectWizardPanel::class.java.getResource("/org/jetbrains/kotlin/kt.png"),
        )
    }

    /** The templates are ordered application, library, script. */
    fun testTemplateOrder() {
        val positions = listOf(
            "KotlinApplicationProject.xml",
            "KotlinLibraryProject.xml",
            "KotlinScriptProject.xml",
        ).map { (template(it).getAttribute("position") as Int) }

        assertEquals(positions.sorted(), positions)
    }

    /** @return the layer file object of the given project template. */
    private fun template(fileName: String): FileObject {
        val directory = FileUtil.getConfigFile("Templates/Project/Kotlin")
        val file = directory?.getFileObject(fileName)
        assertNotNull("template $fileName must exist", file)
        return file!!
    }
}
