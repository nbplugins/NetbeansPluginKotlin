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
package io.github.nbplugins.kotlin.nbm.refactoring

import org.openide.filesystems.FileObject

/**
 * Derives the Kotlin package that a source file must declare after its package directory is renamed.
 *
 * The mapping follows the physical package layout: every directory between the source root and the
 * file becomes one package segment, with the renamed directory contributing its new simple name.
 * This rewrites all descendants by path, including files whose previous directive did not match
 * their directory.
 */
internal object KotlinPackageRenameMapping {

    /**
     * Computes the post-rename package of [file].
     *
     * @param root conventional Kotlin source root that contains [directory]
     * @param directory directory whose simple name changes to [newName]
     * @param newName target simple directory name, already validated by the rename form
     * @param file Kotlin source located directly in [directory] or in one of its descendants
     * @return dotted package derived from the file's path after the rename
     * @throws IllegalArgumentException when the path is not below [root] or a segment is not a
     *                                  plain Kotlin identifier
     */
    fun renamedPackage(root: FileObject, directory: FileObject, newName: String, file: FileObject): String {
        val segments = relativeSegments(root, directory.parent) + newName + relativeSegments(directory, file.parent)
        val invalid = segments.firstOrNull { !IDENTIFIER.matches(it) }
        require(invalid == null) {
            "Cannot derive a Kotlin package from the renamed path " +
                "'${segments.joinToString(".")}': '$invalid' is not a Kotlin identifier."
        }
        return segments.joinToString(".")
    }

    /** Returns [descendant]'s folder names below [ancestor], outermost first. */
    private fun relativeSegments(ancestor: FileObject, descendant: FileObject?): List<String> {
        val segments = mutableListOf<String>()
        var current = descendant
        while (current != null && current != ancestor) {
            segments += current.name
            current = current.parent
        }
        require(current == ancestor) { "${descendant?.path} is not below ${ancestor.path}" }
        return segments.asReversed()
    }

    /** Matches one plain Kotlin package segment accepted by the package-target validation. */
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
