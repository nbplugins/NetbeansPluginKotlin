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

import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper.checkProject
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper.getExtendedClassPath
import org.jetbrains.kotlin.projectsextensions.gradle.classpath.GradleExtendedClassPath
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileUtil
import org.openide.util.Lookup
import java.nio.file.Files

/**
 * Tests for [KotlinProjectHelper.checkProject] and [KotlinProjectHelper.getExtendedClassPath]
 * recognizing Gradle projects from both the old third-party "NetBeans Gradle Support" plugin
 * (`org.netbeans.gradle.project.NbGradleProject`) and Apache NetBeans's built-in Gradle module
 * (`org.netbeans.modules.gradle.NbGradleProjectImpl`).
 */
class KotlinProjectHelperTest : NbTestCase("KotlinProjectHelperTest") {

    fun testCheckProject_recognizesOldThirdPartyGradlePlugin() {
        val baseDir = Files.createTempDirectory("kph_old_gradle").toFile()
        try {
            val fo = FileUtil.toFileObject(baseDir)
            val project = org.netbeans.gradle.project.NbGradleProject(fo, Lookup.EMPTY)

            assertTrue(
                "checkProject() must recognize the old third-party Gradle plugin's project class",
                project.checkProject()
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    fun testCheckProject_recognizesBuiltinGradleModule() {
        val baseDir = Files.createTempDirectory("kph_builtin_gradle").toFile()
        try {
            val fo = FileUtil.toFileObject(baseDir)
            val project = org.netbeans.modules.gradle.NbGradleProjectImpl(fo, Lookup.EMPTY)

            assertTrue(
                "checkProject() must recognize Apache NetBeans's built-in Gradle module's project class",
                project.checkProject()
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    fun testGetExtendedClassPath_returnsGradleExtendedClassPath_forOldThirdPartyGradlePlugin() {
        val baseDir = Files.createTempDirectory("kph_old_gradle_ecp").toFile()
        try {
            val fo = FileUtil.toFileObject(baseDir)
            val project = org.netbeans.gradle.project.NbGradleProject(fo, Lookup.EMPTY)

            assertTrue(
                "getExtendedClassPath() must return a GradleExtendedClassPath for the old plugin's project class",
                project.getExtendedClassPath() is GradleExtendedClassPath
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    fun testGetExtendedClassPath_returnsGradleExtendedClassPath_forBuiltinGradleModule() {
        val baseDir = Files.createTempDirectory("kph_builtin_gradle_ecp").toFile()
        try {
            val fo = FileUtil.toFileObject(baseDir)
            val project = org.netbeans.modules.gradle.NbGradleProjectImpl(fo, Lookup.EMPTY)

            assertTrue(
                "getExtendedClassPath() must return a GradleExtendedClassPath for the built-in module's project class",
                project.getExtendedClassPath() is GradleExtendedClassPath
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
