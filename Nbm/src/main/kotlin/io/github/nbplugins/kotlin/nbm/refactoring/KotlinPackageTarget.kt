/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.refactoring

import org.jetbrains.kotlin.project.KotlinSourceGroup
import org.netbeans.api.java.project.JavaProjectConstants
import org.netbeans.api.project.Project
import org.netbeans.api.project.ProjectUtils
import org.netbeans.api.project.SourceGroup
import org.openide.filesystems.FileObject

/**
 * A selectable source root for a Kotlin refactoring destination.
 *
 * @param path stable NetBeans path of the source-root folder.
 * @param displayName user-visible root label.
 */
data class KotlinPackageTargetRoot(
    val path: String,
    val displayName: String,
    internal val folder: FileObject,
)

/**
 * Resolves Kotlin refactoring target roots, packages, and filesystem directories.
 *
 * @param project NetBeans project that owns the extraction source.
 * @param sourceFile Kotlin file from which the type is extracted.
 */
class KotlinPackageTarget(private val project: Project, private val sourceFile: FileObject) {
    /** Roots to display in the destination selector. */
    val roots: List<KotlinPackageTargetRoot> = findRoots()

    /** Root containing [sourceFile], when its project exposes one. */
    val defaultRootPath: String? = roots.firstOrNull { root ->
        rootFolder(root.path)?.let(::contains) == true
    }?.path

    /** Package declared by [sourceFile], used as the initial package selector value. */
    val defaultPackage: String = sourcePackage()

    /**
     * Lists packages currently represented by folders under [rootPath].
     *
     * @param rootPath selected source-root path.
     * @return sorted package names including the default package.
     */
    fun packages(rootPath: String?): List<String> {
        val root = rootFolder(rootPath) ?: return listOf("")
        val result = linkedSetOf("")
        collectPackages(root, "", result)
        return result.sorted()
    }

    /**
     * Resolves or creates the directory for [packageName] beneath [rootPath].
     *
     * @param rootPath selected source-root path.
     * @param packageName Kotlin package name, empty for the default package.
     * @return target directory, or `null` when the root/package is invalid.
     */
    fun resolveDirectory(rootPath: String?, packageName: String): FileObject? {
        val root = rootFolder(rootPath) ?: return null
        val normalizedPackage = packageName.trim()
        if (!isValidPackage(normalizedPackage)) return null
        var directory = root
        for (segment in normalizedPackage.split('.').filter(String::isNotEmpty)) {
            directory = directory.getFileObject(segment) ?: directory.createFolder(segment)
        }
        return directory
    }

    /** @return whether [packageName] is a legal dotted Kotlin package name. */
    fun isValidPackage(packageName: String): Boolean = packageName.isBlank() ||
        packageName.split('.').all { segment -> IDENTIFIER.matches(segment) }

    /** @return whether [file] is located at or beneath this target's source root. */
    private fun contains(root: FileObject): Boolean = sourceFile.toURI().let { sourceUri ->
        root.toURI().let { rootUri -> sourceUri.toString().startsWith(rootUri.toString()) }
    }

    /** Finds NetBeans Java roots plus Kotlin roots that Maven/Gradle projects expose separately. */
    private fun findRoots(): List<KotlinPackageTargetRoot> {
        val groups = linkedMapOf<String, SourceGroup>()
        ProjectUtils.getSources(project)
            .getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)
            .forEach { group -> groups.putIfAbsent(group.rootFolder.path, group) }
        kotlinRoots().forEach { group -> groups.putIfAbsent(group.rootFolder.path, group) }
        return groups.values.map { group ->
            KotlinPackageTargetRoot(group.rootFolder.path, group.displayName, group.rootFolder)
        }
    }

    /** Locates conventional Kotlin main/test source roots that are absent from Java source groups. */
    private fun kotlinRoots(): List<SourceGroup> = listOf("src/main/kotlin", "src/test/kotlin")
        .mapNotNull { relativePath -> project.projectDirectory.getFileObject(relativePath) }
        .filter(FileObject::isFolder)
        .map(::KotlinSourceGroup)

    /** Returns the selected root object, retaining selection by path rather than UI object identity. */
    private fun rootFolder(path: String?): FileObject? = path?.let { selected ->
        roots.firstOrNull { it.path == selected }?.folder
    }

    /** Recursively collects packages that correspond to folders beneath one source root. */
    private fun collectPackages(folder: FileObject, prefix: String, result: MutableSet<String>) {
        folder.children.filter(FileObject::isFolder).forEach { child ->
            val packageName = if (prefix.isEmpty()) child.name else "$prefix.${child.name}"
            if (isValidPackage(packageName)) {
                result += packageName
                collectPackages(child, packageName, result)
            }
        }
    }

    /** Reads the source package without loading K2 PSI, which keeps dialog construction lightweight. */
    private fun sourcePackage(): String = runCatching {
        Regex("^\\s*package\\s+([^\\s;]+)", RegexOption.MULTILINE)
            .find(sourceFile.asText())?.groupValues?.get(1).orEmpty()
    }.getOrDefault("")

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
