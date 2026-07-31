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
package io.github.nbplugins.kotlin.nbm.resolve

import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper.checkProject
import org.netbeans.api.project.Project
import org.netbeans.api.project.ProjectManager
import org.netbeans.api.project.ui.OpenProjects
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.streams.asSequence

/**
 * Identifies the NetBeans projects that belong to the same build as a Kotlin source project.
 *
 * Refactorings that change inheritance relationships cannot restrict discovery to the module
 * containing the caret: a direct inheritor commonly lives in a sibling module, including one that
 * does not depend on the source module. This service groups Maven modules by their outer reactor
 * POM and Gradle modules by their nearest settings file. It deliberately excludes unrelated open
 * projects.
 */
object BuildProjectScope {
    /** Build-model categories understood by [relatedProjects] and [relatedProjectPaths]. */
    enum class BuildKind {
        /** Maven reactor projects, identified by `pom.xml`. */
        MAVEN,
        /** Gradle multi-project builds, identified by `settings.gradle[.kts]`. */
        GRADLE,
        /** Single-module projects such as Ant/J2SE. */
        STANDALONE,
    }

    /**
     * Returns Kotlin-capable open projects that share the build containing [owner].
     *
     * @param owner project containing the invoked Kotlin refactoring.
     * @return the owner and every open sibling module in the same Maven reactor or Gradle build.
     */
    fun relatedProjects(owner: Project): List<Project> {
        val kind = buildKind(owner)
        val ownerPath = toPath(owner) ?: return listOf(owner)
        val root = buildRoot(ownerPath, kind) ?: return listOf(owner)
        val candidates = linkedMapOf<Path, Project>()
        (OpenProjects.getDefault().openProjects.asList() + owner)
            .filter { it.checkProject() }
            .forEach { project -> toPath(project)?.let { candidates[it] = project } }
        discoverProjects(root, kind).forEach { project -> toPath(project)?.let { candidates[it] = project } }
        val paths = relatedProjectPaths(ownerPath, candidates.keys, kind).toSet()
        return candidates.filter { (path, _) -> path in paths }.values.toList()
            .ifEmpty { listOf(owner) }
    }

    /**
     * Selects paths from [candidatePaths] that share [ownerPath]'s build root.
     *
     * This pure path-level form is used by unit tests and keeps build-boundary semantics independent
     * of NetBeans project-model initialization.
     *
     * @param ownerPath directory of the project containing the source declaration.
     * @param candidatePaths directories of projects eligible for discovery.
     * @param buildKind build model associated with [ownerPath].
     * @return normalized owner-inclusive paths belonging to the same build.
     */
    fun relatedProjectPaths(
        ownerPath: Path,
        candidatePaths: Collection<Path>,
        buildKind: BuildKind,
    ): List<Path> {
        val owner = ownerPath.toAbsolutePath().normalize()
        if (buildKind == BuildKind.STANDALONE) return listOf(owner)
        val root = buildRoot(owner, buildKind) ?: return listOf(owner)
        return (candidatePaths + owner)
            .asSequence()
            .map { it.toAbsolutePath().normalize() }
            .filter { buildRoot(it, buildKind) == root }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * Determines the build type from the NetBeans project implementation.
     *
     * @param project candidate project.
     * @return its supported build category.
     */
    fun buildKind(project: Project): BuildKind = when (project::class.java.name) {
        "org.netbeans.modules.maven.NbMavenProjectImpl" -> BuildKind.MAVEN
        "org.netbeans.gradle.project.NbGradleProject",
        "org.netbeans.modules.gradle.NbGradleProjectImpl" -> BuildKind.GRADLE
        else -> BuildKind.STANDALONE
    }

    /** Returns a normalized filesystem directory for [project], if it has a disk backing. */
    private fun toPath(project: Project): Path? =
        FileUtil.toFile(project.projectDirectory)?.toPath()?.toAbsolutePath()?.normalize()

    /**
     * Finds NetBeans projects beneath a build root without depending on them being open.
     *
     * Maven project discovery is intentionally filesystem-based: the plugin's existing Maven helper
     * already tolerates project-model startup races, while the NetBeans project manager knows how to
     * recognize nested module directories. Gradle discovery follows the same path because only the
     * root settings file reliably identifies a multi-project build across both Gradle integrations.
     */
    private fun discoverProjects(root: Path, kind: BuildKind): Sequence<Project> = sequence {
        if (kind == BuildKind.STANDALONE || !root.isDirectory()) return@sequence
        val modulePaths = Files.walk(root).use { paths ->
            paths.asSequence()
                .filter(Path::isDirectory)
                .filter { candidate ->
                    when (kind) {
                        BuildKind.MAVEN -> Files.isRegularFile(candidate.resolve("pom.xml"))
                        BuildKind.GRADLE -> candidate == root ||
                            Files.isRegularFile(candidate.resolve("build.gradle")) ||
                            Files.isRegularFile(candidate.resolve("build.gradle.kts"))
                        BuildKind.STANDALONE -> false
                    }
                }
                .toList()
        }
        for (modulePath in modulePaths) {
            val directory = toFileObject(modulePath) ?: continue
            findProject(directory)?.let { yield(it) }
        }
    }

    /** Maps a disk directory to its NetBeans file object. */
    private fun toFileObject(path: Path): FileObject? = FileUtil.toFileObject(path.toFile())

    /** Loads a NetBeans project only when the directory is recognized as a project. */
    private fun findProject(directory: FileObject): Project? = runCatching {
        ProjectManager.getDefault().takeIf { it.isProject(directory) }?.findProject(directory)
    }.getOrNull()

    /** Finds the reactor/build root enclosing [projectPath]. */
    private fun buildRoot(projectPath: Path, kind: BuildKind): Path? = when (kind) {
        BuildKind.MAVEN -> projectPath.ancestors()
            .filter { Files.isRegularFile(it.resolve("pom.xml")) }
            .lastOrNull()
        BuildKind.GRADLE -> projectPath.ancestors()
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle")) || Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        BuildKind.STANDALONE -> null
    }

    /** Enumerates a path and every ancestor through the filesystem root. */
    private fun Path.ancestors(): Sequence<Path> = sequence {
        var current: Path? = this@ancestors
        while (current != null) {
            yield(current)
            current = current.parent
        }
    }
}
