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
 * Package named by the `import` directive under the editor caret.
 *
 * Change Kotlin Package edits package directives and their references only, so the action can do
 * something useful only when project Kotlin sources declare the imported package.
 */
internal sealed class KotlinImportPackageTarget {

    /**
     * A package declared by Kotlin files of the current project.
     *
     * @param packageName dotted package name that the refactoring will replace
     * @param files Kotlin files that declare [packageName]
     */
    internal data class Declared(val packageName: String, val files: List<FileObject>) : KotlinImportPackageTarget()

    /**
     * A package that no project Kotlin source declares, such as a library package.
     *
     * @param packageName dotted imported package; empty when the import targets the root package
     */
    internal data class External(val packageName: String) : KotlinImportPackageTarget()

    internal companion object {
        /**
         * Computes the package names an import can refer to, longest (most specific) first.
         *
         * A declaration import (`import a.b.C.D`) can name a package at any dotted prefix, because
         * the trailing segments may be nested classes. A star import names exactly one package.
         *
         * @param importPath dotted path written after `import`
         * @param allUnder whether the directive imports a whole package
         * @return candidate package names; empty when no dotted package can be derived
         */
        fun candidates(importPath: String, allUnder: Boolean): List<String> {
            val segments = importPath.split('.').map(String::trim).filter(String::isNotEmpty)
            if (segments.isEmpty()) return emptyList()
            if (allUnder) return listOf(segments.joinToString("."))
            val packageSegments = segments.dropLast(1)
            return (packageSegments.size downTo 1).map { count ->
                packageSegments.take(count).joinToString(".")
            }
        }

        /**
         * Resolves the package that an import directive names.
         *
         * @param importPath dotted path written after `import`
         * @param allUnder whether the directive imports a whole package
         * @param filesInPackage finds Kotlin files that declare a given package
         * @return the declared package, an external package marker, or `null` for a blank path
         */
        fun resolve(
            importPath: String,
            allUnder: Boolean,
            filesInPackage: (String) -> List<FileObject>,
        ): KotlinImportPackageTarget? {
            if (importPath.isBlank()) return null
            val candidates = candidates(importPath, allUnder)
            candidates.forEach { packageName ->
                val files = filesInPackage(packageName)
                if (files.isNotEmpty()) return Declared(packageName, files)
            }
            return External(candidates.firstOrNull() ?: importPath.substringBeforeLast('.', ""))
        }
    }
}
