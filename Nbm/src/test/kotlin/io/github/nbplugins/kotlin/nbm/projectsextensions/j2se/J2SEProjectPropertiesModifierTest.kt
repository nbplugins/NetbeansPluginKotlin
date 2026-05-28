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
package io.github.nbplugins.kotlin.nbm.projectsextensions.j2se

import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileUtil
import org.openide.util.Lookup
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * Tests that [J2SEProjectPropertiesModifier.turnOffCompileOnSave] correctly modifies
 * `nbproject/private/private.properties` without relying on `PropertyUtils`.
 */
class J2SEProjectPropertiesModifierTest : NbTestCase("J2SEProjectPropertiesModifierTest") {

    /** Creates the required directory/file structure under a temp folder. */
    private fun createProjectStructure(root: File, initialProps: String): File {
        val privateDir = File(root, "nbproject/private")
        privateDir.mkdirs()
        val propsFile = File(privateDir, "private.properties")
        propsFile.writeText(initialProps)
        return root
    }

    fun testTurnOffCompileOnSave_setsProperty() {
        val root = Files.createTempDirectory("j2setest").toFile()
        try {
            createProjectStructure(root, "compile.on.save=true\n")

            val rootFo = FileUtil.toFileObject(root)
            val project = object : org.netbeans.api.project.Project {
                override fun getProjectDirectory() = rootFo
                override fun getLookup(): Lookup = Lookup.EMPTY
            }

            J2SEProjectPropertiesModifier(project).turnOffCompileOnSave()

            val result = Properties()
            File(root, "nbproject/private/private.properties").inputStream().use { result.load(it) }
            assertEquals("compile.on.save should be false", "false", result.getProperty("compile.on.save"))
        } finally {
            root.deleteRecursively()
        }
    }

    fun testTurnOffCompileOnSave_noNbproject_doesNotThrow() {
        val root = Files.createTempDirectory("j2setest_empty").toFile()
        try {
            val rootFo = FileUtil.toFileObject(root)
            val project = object : org.netbeans.api.project.Project {
                override fun getProjectDirectory() = rootFo
                override fun getLookup(): Lookup = Lookup.EMPTY
            }
            // must return silently without exception
            J2SEProjectPropertiesModifier(project).turnOffCompileOnSave()
        } finally {
            root.deleteRecursively()
        }
    }
}
