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

import org.netbeans.api.project.Project
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.util.Lookup

/** Tests the Kotlin source groups contributed to a project's merged Sources lookup. */
class KotlinProjectSourcesTest : NbTestCase("KotlinProjectSourcesTest") {

    /** Exposes main and test roots only through their respective Kotlin source types. */
    fun testGetSourceGroups_separatesMainAndTestRoots() {
        val projectDir = projectDirectory()
        val main = FileUtil.createFolder(projectDir, "src/main/kotlin")
        val test = FileUtil.createFolder(projectDir, "src/test/kotlin")
        val sources = KotlinProjectSources(project(projectDir))

        assertEquals(
            listOf(main),
            sources.getSourceGroups(KotlinProjectSourceGroups.KOTLIN_SOURCE_TYPE).map { it.rootFolder }
        )
        assertEquals(
            listOf(test),
            sources.getSourceGroups(KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_TYPE).map { it.rootFolder }
        )
        assertTrue(sources.getSourceGroups("java").isEmpty())
    }

    /** Uses Kotlin-specific labels instead of leaking the folder name into Project Explorer. */
    fun testGetSourceGroups_usesKotlinPackageLabels() {
        val projectDir = projectDirectory()
        FileUtil.createFolder(projectDir, "src/main/kotlin")
        FileUtil.createFolder(projectDir, "src/test/kotlin")
        val sources = KotlinProjectSources(project(projectDir))

        assertEquals(
            KotlinProjectSourceGroups.KOTLIN_SOURCE_PACKAGES,
            sources.getSourceGroups(KotlinProjectSourceGroups.KOTLIN_SOURCE_TYPE).single().displayName
        )
        assertEquals(
            KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_PACKAGES,
            sources.getSourceGroups(KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_TYPE).single().displayName
        )
    }

    /** Creates an isolated project directory for one test. */
    private fun projectDirectory(): FileObject {
        clearWorkDir()
        return FileUtil.toFileObject(workDir)
    }

    /** Returns a minimal project backed by [directory]. */
    private fun project(directory: FileObject): Project = object : Project {
        override fun getProjectDirectory(): FileObject = directory
        override fun getLookup(): Lookup = Lookup.EMPTY
    }
}
