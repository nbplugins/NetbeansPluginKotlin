package org.jetbrains.kotlin.projectsextensions.j2se

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
