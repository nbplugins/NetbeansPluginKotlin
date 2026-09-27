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
import org.netbeans.api.project.SourceGroup
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil

/** Tests filtering Kotlin directories from a broad Ant/J2SE Java source group. */
class FilteredSourceGroupTest : NbTestCase("FilteredSourceGroupTest") {

    /** Hides a Kotlin subtree while retaining Java packages in the same broad root. */
    fun testContains_excludesKotlinSubtree() {
        clearWorkDir()
        val root = FileUtil.toFileObject(workDir)
        val javaFile = FileUtil.createData(root, "src/main/java/sample/JavaFile.java")
        val kotlinFile = FileUtil.createData(root, "src/main/kotlin/sample/KotlinFile.kt")
        val javaRoot = root.getFileObject("src")!!
        val kotlinRoot = root.getFileObject("src/main/kotlin")!!

        val filtered = FilteredSourceGroup(TestSourceGroup(javaRoot), listOf(kotlinRoot))

        assertTrue(filtered.contains(javaFile))
        assertFalse(filtered.contains(kotlinFile))
        assertFalse(filtered.contains(kotlinRoot))
    }

    /** Minimal source group used to test filtering independently from a project type. */
    private class TestSourceGroup(private val root: FileObject) : SourceGroup {
        override fun getRootFolder(): FileObject = root
        override fun getName(): String = root.path
        override fun getDisplayName(): String = "Source Packages"
        override fun getIcon(opened: Boolean): Icon? = null
        override fun contains(file: FileObject): Boolean = file == root || file.path.startsWith("${root.path}/")
        override fun addPropertyChangeListener(listener: PropertyChangeListener?) = Unit
        override fun removePropertyChangeListener(listener: PropertyChangeListener?) = Unit
    }
}
