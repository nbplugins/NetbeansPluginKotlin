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
 * Immutable Kotlin sources selected for a Change Kotlin Package operation.
 *
 * A directory selection changes only package directives. Its own directory name is therefore not
 * appended to the user-entered package; only descendant directory segments are retained.
 *
 * @param sourceFiles physical Kotlin sources in deterministic relative-path order
 * @param selectedDirectory selected folder, or `null` for one editor-selected file
 */
class KotlinChangePackageSourceSelection private constructor(
    val sourceFiles: List<FileObject>,
    val selectedDirectory: FileObject?,
) {
    /** @return whether this selection originated from a folder node. */
    val isDirectory: Boolean
        get() = selectedDirectory != null

    /** @return source used for project discovery and concise UI details. */
    val representativeFile: FileObject
        get() = sourceFiles.first()

    /** @return concise label for the selected file or folder. */
    val displayName: String
        get() = selectedDirectory?.nameExt ?: representativeFile.nameExt

    /**
     * Computes relative descendant folders to append below the user-selected target package.
     *
     * @param file a member of [sourceFiles]
     * @return empty for one file or a file directly in the selected folder
     */
    fun targetRelativePackageSegments(file: FileObject): List<String> {
        require(file in sourceFiles) { "File is not part of this Change Package selection: ${file.path}" }
        val directory = selectedDirectory ?: return emptyList()
        return relativeSegments(directory, file.parent)
    }

    companion object {
        /**
         * Creates a selection from one Kotlin file or recursively from one folder.
         *
         * @param source physical file or folder selected by the user
         * @return a non-empty Kotlin selection, or `null` for unsupported content
         */
        fun from(source: FileObject): KotlinChangePackageSourceSelection? {
            if (!source.isValid) return null
            if (!source.isFolder) {
                return if (source.ext == "kt") KotlinChangePackageSourceSelection(listOf(source), null) else null
            }
            val files = collectKotlinFiles(source).sortedBy(FileObject::getPath)
            return files.takeIf(List<FileObject>::isNotEmpty)?.let { KotlinChangePackageSourceSelection(it, source) }
        }

        /** Recursively finds valid Kotlin files while deliberately leaving all other content alone. */
        private fun collectKotlinFiles(folder: FileObject): List<FileObject> = folder.children.flatMap { child ->
            when {
                child.isFolder -> collectKotlinFiles(child)
                child.isValid && child.ext == "kt" -> listOf(child)
                else -> emptyList()
            }
        }

        /** Returns [descendant]'s relative folder segments below [ancestor]. */
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
