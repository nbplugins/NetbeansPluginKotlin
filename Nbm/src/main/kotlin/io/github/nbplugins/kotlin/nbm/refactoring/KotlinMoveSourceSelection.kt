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
 * Immutable Kotlin files selected for a Move Kotlin File or Move Kotlin Directory operation.
 *
 * A directory selection retains its leaf directory below the package chosen in the destination UI.
 * For example, moving `feature/internal/Helper.kt` with selected directory `feature` to package
 * `sample.target` places the file below `sample/target/feature/internal`.
 *
 * @param sourceFiles physical Kotlin source files in deterministic relative-path order
 * @param selectedDirectory directory selected by the user, or `null` for a file selection
 */
class KotlinMoveSourceSelection private constructor(
    val sourceFiles: List<FileObject>,
    val selectedDirectory: FileObject?,
) {
    /** @return whether this selection was created from a directory node. */
    val isDirectory: Boolean
        get() = selectedDirectory != null

    /** @return source used for project/source-root discovery and single-file UI details. */
    val representativeFile: FileObject
        get() = sourceFiles.first()

    /** @return concise UI label for the selected source. */
    val displayName: String
        get() = selectedDirectory?.nameExt ?: representativeFile.nameExt

    /**
     * Computes the final relative folder segments beneath the user-selected target package.
     *
     * @param file a member of [sourceFiles]
     * @return empty for a directly selected file, otherwise selected-directory name plus the file's
     *         relative parent path
     */
    fun targetRelativeFolderSegments(file: FileObject): List<String> {
        require(file in sourceFiles) { "File is not part of this Move Kotlin selection: ${file.path}" }
        val directory = selectedDirectory ?: return emptyList()
        val relativeParent = relativeSegments(directory, file.parent)
        return listOf(directory.name) + relativeParent
    }

    /**
     * Creates a selection from one Kotlin file or recursively from one directory.
     *
     * @param source selected physical Kotlin file or folder
     * @return a non-empty selection, or `null` if [source] contains no valid Kotlin files
     */
    companion object {
        /**
         * Creates a Kotlin move selection from one physical file or recursively from one folder.
         *
         * @param source selected physical Kotlin file or source-tree folder
         * @return a non-empty selection, or `null` when [source] has no valid Kotlin descendants
         */
        fun from(source: FileObject): KotlinMoveSourceSelection? {
            if (!source.isValid) return null
            if (!source.isFolder) {
                return if (source.ext == "kt") KotlinMoveSourceSelection(listOf(source), null) else null
            }
            val files = collectKotlinFiles(source).sortedBy { it.path }
            return files.takeIf(List<FileObject>::isNotEmpty)?.let { KotlinMoveSourceSelection(it, source) }
        }

        /** Recursively finds valid Kotlin source files while deliberately ignoring other content. */
        private fun collectKotlinFiles(folder: FileObject): List<FileObject> = folder.children.flatMap { child ->
            when {
                child.isFolder -> collectKotlinFiles(child)
                child.isValid && child.ext == "kt" -> listOf(child)
                else -> emptyList()
            }
        }

        /** Returns [descendant]'s relative folder path below [ancestor]. */
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
    }
}
