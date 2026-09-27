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

import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil

/** Tests conventional Kotlin source-root discovery for Project Explorer. */
class KotlinProjectSourceGroupsTest : NbTestCase("KotlinProjectSourceGroupsTest") {

    /** Creates main and test Kotlin roots with their expected Explorer labels. */
    fun testFindRoots_discoversConventionalMainAndTestRoots() {
        val projectDir = projectDirectory()
        val main = FileUtil.createFolder(projectDir, "src/main/kotlin")
        val test = FileUtil.createFolder(projectDir, "src/test/kotlin")

        val roots = KotlinProjectSourceGroups.findRoots(projectDir)

        assertEquals(listOf(main, test), roots.map(KotlinProjectSourceRoot::folder))
        assertEquals(
            listOf("Kotlin Source Packages", "Kotlin Test Packages"),
            roots.map(KotlinProjectSourceRoot::displayName)
        )
    }

    /** Omits Kotlin roots that do not exist instead of creating empty Explorer nodes. */
    fun testFindRoots_omitsMissingRoots() {
        val projectDir = projectDirectory()
        val main = FileUtil.createFolder(projectDir, "src/main/kotlin")

        val roots = KotlinProjectSourceGroups.findRoots(projectDir)

        assertEquals(listOf(main), roots.map(KotlinProjectSourceRoot::folder))
    }

    /** Keeps source-root results deterministic even when a folder is supplied more than once. */
    fun testCreateSourceGroups_deduplicatesRoots() {
        val projectDir = projectDirectory()
        val main = FileUtil.createFolder(projectDir, "src/main/kotlin")

        val groups = KotlinProjectSourceGroups.createSourceGroups(
            listOf(
                KotlinProjectSourceRoot(main, false),
                KotlinProjectSourceRoot(main, false)
            )
        )

        assertEquals(1, groups.size)
        assertEquals(main, groups.single().rootFolder)
    }

    /** Creates an isolated project folder for one test. */
    private fun projectDirectory(): FileObject {
        clearWorkDir()
        return FileUtil.toFileObject(workDir)
    }
}
