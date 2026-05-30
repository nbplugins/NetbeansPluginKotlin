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
package io.github.nbplugins.kotlin.nbm.projectsextensions.maven

import org.netbeans.api.project.Project
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileUtil
import org.openide.util.Lookup
import java.io.File
import java.nio.file.Files

/**
 * Tests for [MavenHelper.getKotlinPluginSourceDirs].
 *
 * Uses reflection-compatible stub objects to simulate the Maven project model
 * without requiring a live Maven project.
 */
class MavenHelperTest : NbTestCase("MavenHelperTest") {

    // ── Stub helpers ─────────────────────────────────────────────────────────

    /** Simulates an Xpp3Dom node whose getChildren("source") returns [values]. */
    private fun makeSourceDirsNode(values: List<String>): Any =
        object {
            @Suppress("unused")
            fun getChildren(name: String): Array<Any> = when (name) {
                "source" -> values.map { v -> object { @Suppress("unused") fun getValue() = v } }
                    .toTypedArray()
                else -> emptyArray()
            }
        }

    /** Simulates an Xpp3Dom execution configuration with <sourceDirs>. */
    private fun makeConfig(baseDir: File, sourcePaths: List<String>): Any {
        val sourceDirsNode = makeSourceDirsNode(
            sourcePaths.map { "\${project.basedir}/${it.removePrefix("/")}" }
        )
        return object {
            @Suppress("unused")
            fun getChild(name: String): Any? = if (name == "sourceDirs") sourceDirsNode else null
        }
    }

    /** Simulates a configuration with plain relative paths (no {@code ${project.basedir}} prefix). */
    private fun makeConfigRelative(sourcePaths: List<String>): Any {
        val sourceDirsNode = makeSourceDirsNode(sourcePaths)
        return object {
            @Suppress("unused")
            fun getChild(name: String): Any? = if (name == "sourceDirs") sourceDirsNode else null
        }
    }

    /** Simulates a Maven plugin execution with configuration. */
    private fun makeExecution(config: Any): Any =
        object {
            @Suppress("unused")
            fun getConfiguration() = config
        }

    /** Simulates an org.apache.maven.model.Plugin. */
    private fun makePlugin(groupId: String, artifactId: String, executions: List<Any>): Any =
        object {
            @Suppress("unused")
            fun getGroupId() = groupId
            @Suppress("unused")
            fun getArtifactId() = artifactId
            @Suppress("unused")
            fun getExecutions(): List<Any> = executions
        }

    /** Simulates an org.apache.maven.project.MavenProject. */
    private fun makeMavenProject(baseDir: File, plugins: List<Any>): Any =
        object {
            @Suppress("unused")
            fun getBasedir(): File = baseDir
            @Suppress("unused")
            fun getBuildPlugins(): List<Any> = plugins
        }

    /**
     * Simulates a NetBeans Maven project: implements [Project] AND exposes
     * `getOriginalMavenProject()` so that [MavenHelper.getOriginalMavenProject]
     * finds it via reflection.
     */
    private fun makeMavenNbProject(baseDir: File, mavenProject: Any): Project {
        val fo = FileUtil.toFileObject(baseDir)
        return object : Project {
            override fun getProjectDirectory() = fo
            override fun getLookup(): Lookup = Lookup.EMPTY
            @Suppress("unused")
            fun getOriginalMavenProject() = mavenProject
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Happy path: kotlin-maven-plugin is present with <sourceDirs>;
     * both <source> paths should be returned with basedir resolved.
     */
    fun testGetKotlinPluginSourceDirs_extractsSourceDirs() {
        val baseDir = Files.createTempDirectory("mvnhelper_happy").toFile()
        try {
            val config = makeConfig(baseDir, listOf("src/main/kotlin", "src/main/java"))
            val plugin = makePlugin("org.jetbrains.kotlin", "kotlin-maven-plugin", listOf(makeExecution(config)))
            val project = makeMavenNbProject(baseDir, makeMavenProject(baseDir, listOf(plugin)))

            val result = MavenHelper.getKotlinPluginSourceDirs(project)

            assertEquals(2, result.size)
            assertTrue("should contain src/main/kotlin",
                result.any { it.endsWith("src${File.separator}main${File.separator}kotlin") })
            assertTrue("should contain src/main/java",
                result.any { it.endsWith("src${File.separator}main${File.separator}java") })
        } finally {
            baseDir.deleteRecursively()
        }
    }

    /**
     * When kotlin-maven-plugin is absent, result must be empty.
     */
    fun testGetKotlinPluginSourceDirs_returnsEmpty_whenPluginAbsent() {
        val baseDir = Files.createTempDirectory("mvnhelper_noplugin").toFile()
        try {
            val plugin = makePlugin("some.other", "other-plugin", emptyList())
            val project = makeMavenNbProject(baseDir, makeMavenProject(baseDir, listOf(plugin)))

            val result = MavenHelper.getKotlinPluginSourceDirs(project)

            assertTrue("result must be empty when kotlin-maven-plugin is absent", result.isEmpty())
        } finally {
            baseDir.deleteRecursively()
        }
    }

    /**
     * When `getOriginalMavenProject()` returns null (e.g. project model not loaded),
     * [MavenHelper.getKotlinPluginSourceDirs] must return an empty list without throwing.
     */
    fun testGetKotlinPluginSourceDirs_returnsEmpty_whenMavenProjectIsNull() {
        val baseDir = Files.createTempDirectory("mvnhelper_null").toFile()
        try {
            val fo = FileUtil.toFileObject(baseDir)
            val project = object : Project {
                override fun getProjectDirectory() = fo
                override fun getLookup(): Lookup = Lookup.EMPTY
                // returns null — simulates project model not yet loaded
                @Suppress("unused")
                fun getOriginalMavenProject(): Any? = null
            }

            val result = MavenHelper.getKotlinPluginSourceDirs(project)

            assertTrue("result must be empty when MavenProject is null", result.isEmpty())
        } finally {
            baseDir.deleteRecursively()
        }
    }

    /**
     * Paths written as plain relative values (no {@code ${project.basedir}} prefix)
     * must be resolved against the project base directory, not the JVM working directory.
     */
    fun testGetKotlinPluginSourceDirs_resolvesRelativePaths() {
        val baseDir = Files.createTempDirectory("mvnhelper_rel").toFile()
        try {
            val config = makeConfigRelative(listOf("src/main/kotlin", "src/main/java"))
            val plugin = makePlugin("org.jetbrains.kotlin", "kotlin-maven-plugin", listOf(makeExecution(config)))
            val project = makeMavenNbProject(baseDir, makeMavenProject(baseDir, listOf(plugin)))

            val result = MavenHelper.getKotlinPluginSourceDirs(project)

            assertEquals(2, result.size)
            assertTrue("relative path must be resolved under baseDir",
                result.any { it == File(baseDir, "src/main/kotlin").absolutePath })
            assertTrue("relative path must be resolved under baseDir",
                result.any { it == File(baseDir, "src/main/java").absolutePath })
        } finally {
            baseDir.deleteRecursively()
        }
    }

    /**
     * Duplicate paths across executions must be deduplicated.
     */
    fun testGetKotlinPluginSourceDirs_deduplicates() {
        val baseDir = Files.createTempDirectory("mvnhelper_dup").toFile()
        try {
            val config1 = makeConfig(baseDir, listOf("src/main/kotlin"))
            val config2 = makeConfig(baseDir, listOf("src/main/kotlin", "src/main/java"))
            val plugin = makePlugin(
                "org.jetbrains.kotlin", "kotlin-maven-plugin",
                listOf(makeExecution(config1), makeExecution(config2))
            )
            val project = makeMavenNbProject(baseDir, makeMavenProject(baseDir, listOf(plugin)))

            val result = MavenHelper.getKotlinPluginSourceDirs(project)

            assertEquals("duplicates must be removed", 2, result.size)
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
