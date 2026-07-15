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
package org.jetbrains.kotlin.projectsextensions.gradle.classpath

import org.jetbrains.kotlin.project.KotlinSourceGroup
import org.netbeans.api.java.classpath.ClassPath
import org.netbeans.api.java.project.JavaProjectConstants
import org.netbeans.api.project.Project
import org.netbeans.api.project.SourceGroup
import org.netbeans.api.project.Sources
import org.netbeans.junit.NbTestCase
import org.netbeans.spi.java.classpath.ClassPathProvider
import org.netbeans.spi.java.classpath.support.ClassPathSupport
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import java.nio.file.Files
import javax.swing.event.ChangeListener

/**
 * Tests for [GradleExtendedClassPath] resolving a classpath both from the old third-party
 * Gradle plugin's file-less `getClassPaths(String)` accessor and from the standard
 * [ClassPathProvider.findClassPath] used by Apache NetBeans's built-in Gradle module.
 */
class GradleExtendedClassPathTest : NbTestCase("GradleExtendedClassPathTest") {

    private fun makeProjectDir(prefix: String): FileObject {
        val baseDir = Files.createTempDirectory(prefix).toFile()
        return FileUtil.toFileObject(baseDir)!!
    }

    private fun makeProject(projectDirectory: FileObject, lookup: Lookup): Project =
        object : Project {
            override fun getProjectDirectory(): FileObject = projectDirectory
            override fun getLookup(): Lookup = lookup
        }

    /** Simulates the old third-party plugin's provider: a file-less `getClassPaths(String)` accessor. */
    private class OldApiClassPathProvider(private val classPath: ClassPath) : ClassPathProvider {
        override fun findClassPath(file: FileObject?, type: String?): ClassPath? = null

        @Suppress("unused")
        fun getClassPaths(type: String): ClassPath = classPath
    }

    /** Simulates the modern built-in module's provider: only the standard SPI method. */
    private class StandardClassPathProvider(private val classPath: ClassPath) : ClassPathProvider {
        override fun findClassPath(file: FileObject?, type: String?): ClassPath = classPath
    }

    /**
     * Simulates Apache NetBeans's built-in Gradle module's `GradleSourcesImpl`, which only
     * registers source groups under `"java"`/`"kotlin"` types — never [Sources.TYPE_GENERIC].
     */
    private class JavaAndKotlinOnlySources(private val root: FileObject) : Sources {
        override fun getSourceGroups(type: String): Array<SourceGroup> =
            if (type == JavaProjectConstants.SOURCES_TYPE_JAVA || type == "kotlin")
                arrayOf(KotlinSourceGroup(root))
            else emptyArray()

        override fun addChangeListener(listener: ChangeListener?) {}
        override fun removeChangeListener(listener: ChangeListener?) {}
    }

    fun testGetProjectSourcesClassPath_usesOldGetClassPathsApi_whenPresent() {
        val projectDir = makeProjectDir("gecp_old")
        val expected = ClassPathSupport.createClassPath(projectDir)
        val provider = OldApiClassPathProvider(expected)
        val project = makeProject(projectDir, Lookups.fixed(provider))

        val result = GradleExtendedClassPath(project).getProjectSourcesClassPath(ClassPath.COMPILE)

        assertEquals("must resolve classpath via the old getClassPaths(String) accessor",
            expected, result)
    }

    fun testGetProjectSourcesClassPath_fallsBackToStandardFindClassPath_whenOldApiAbsent() {
        val projectDir = makeProjectDir("gecp_standard")
        val expected = ClassPathSupport.createClassPath(projectDir)
        val provider = StandardClassPathProvider(expected)
        val project = makeProject(projectDir, Lookups.fixed(provider))

        val result = GradleExtendedClassPath(project).getProjectSourcesClassPath(ClassPath.COMPILE)

        assertEquals(
            "must fall back to the standard ClassPathProvider.findClassPath(FileObject, String) API",
            expected.entries().map { it.url }, result.entries().map { it.url }
        )
    }

    /**
     * Regression test: Apache NetBeans's built-in Gradle module registers source groups only
     * under `"java"`/`"kotlin"` types, not [Sources.TYPE_GENERIC]. Querying only TYPE_GENERIC
     * (as an earlier version of the fallback did) finds zero source roots and silently
     * resolves to an empty classpath even though a real classpath is available.
     */
    fun testGetProjectSourcesClassPath_fallsBack_whenSourceGroupsAreJavaTypeOnly() {
        val projectDir = makeProjectDir("gecp_javatype")
        val expected = ClassPathSupport.createClassPath(projectDir)
        val provider = StandardClassPathProvider(expected)
        val sources = JavaAndKotlinOnlySources(projectDir)
        val project = makeProject(projectDir, Lookups.fixed(provider, sources))

        val result = GradleExtendedClassPath(project).getProjectSourcesClassPath(ClassPath.COMPILE)

        assertEquals(
            "must query java/kotlin source-group types, not just TYPE_GENERIC",
            expected.entries().map { it.url }, result.entries().map { it.url }
        )
    }

    fun testGetProjectSourcesClassPath_returnsEmpty_whenNoProviderPresent() {
        val projectDir = makeProjectDir("gecp_none")
        val project = makeProject(projectDir, Lookup.EMPTY)

        val result = GradleExtendedClassPath(project).getProjectSourcesClassPath(ClassPath.COMPILE)

        assertEquals("must return ClassPath.EMPTY when no ClassPathProvider is present",
            0, result.entries().size)
    }
}
