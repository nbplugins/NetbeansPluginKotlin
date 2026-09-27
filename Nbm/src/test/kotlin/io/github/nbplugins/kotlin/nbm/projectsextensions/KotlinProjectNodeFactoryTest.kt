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

import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.event.ChangeListener
import org.netbeans.api.project.Project
import org.netbeans.api.project.SourceGroup
import org.netbeans.api.project.Sources
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups

/** Tests the source-root order and language separation supplied to Project Explorer node factories. */
class KotlinProjectNodeFactoryTest : NbTestCase("KotlinProjectNodeFactoryTest") {

    /** Separates Java and Kotlin main/test groups when replacing the Gradle source node factory. */
    fun testGradleFactory_separatesJavaAndKotlinGroups() {
        clearWorkDir()
        val root = FileUtil.toFileObject(workDir)
        val javaMain = FileUtil.createFolder(root, "src/main/java")
        val javaTest = FileUtil.createFolder(root, "src/test/java")
        val kotlinMain = FileUtil.createFolder(root, "src/main/kotlin")
        val kotlinTest = FileUtil.createFolder(root, "src/test/kotlin")
        val standardSources = TestSources(
            mapOf(
                "java" to arrayOf(TestSourceGroup(javaMain, "Source Packages"), TestSourceGroup(javaTest, "Test Packages")),
                "kotlin" to arrayOf(TestSourceGroup(kotlinMain, "Source Packages [kotlin]"), TestSourceGroup(kotlinTest, "Test Packages [kotlin]")),
                KotlinProjectSourceGroups.KOTLIN_SOURCE_TYPE to arrayOf(
                    TestSourceGroup(kotlinMain, KotlinProjectSourceGroups.KOTLIN_SOURCE_PACKAGES)
                ),
                KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_TYPE to arrayOf(
                    TestSourceGroup(kotlinTest, KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_PACKAGES)
                )
            )
        )
        val project = TestProject(root, standardSources)

        val groups = KotlinGradleProjectNodeFactory().createNodes(project).keys()

        assertEquals(
            listOf(javaMain, javaTest, kotlinMain, kotlinTest),
            groups.map(SourceGroup::getRootFolder)
        )
        assertEquals(
            listOf(
                "Source Packages", "Test Packages",
                KotlinProjectSourceGroups.KOTLIN_SOURCE_PACKAGES,
                KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_PACKAGES
            ),
            groups.map(SourceGroup::getDisplayName)
        )
    }

    /** Minimal source provider with preconfigured type-to-group mappings. */
    private class TestSources(private val groups: Map<String, Array<SourceGroup>>) : Sources {
        override fun getSourceGroups(type: String): Array<SourceGroup> = groups[type] ?: emptyArray()
        override fun addChangeListener(listener: ChangeListener?) = Unit
        override fun removeChangeListener(listener: ChangeListener?) = Unit
    }

    /** Project whose lookup exposes the test source provider directly. */
    private class TestProject(private val root: FileObject, sources: Sources) : Project {
        private val lookup = Lookups.fixed(this, sources)
        override fun getProjectDirectory(): FileObject = root
        override fun getLookup(): Lookup = lookup
    }

    /** Immutable source group for test input. */
    private class TestSourceGroup(private val root: FileObject, private val label: String) : SourceGroup {
        override fun getRootFolder(): FileObject = root
        override fun getName(): String = root.path
        override fun getDisplayName(): String = label
        override fun getIcon(opened: Boolean): Icon? = null
        override fun contains(file: FileObject): Boolean = file == root || file.path.startsWith("${root.path}/")
        override fun addPropertyChangeListener(listener: PropertyChangeListener?) = Unit
        override fun removePropertyChangeListener(listener: PropertyChangeListener?) = Unit
    }
}
