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

import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectSourceGroups
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectSourceRoot
import org.netbeans.api.project.FileOwnerQuery
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.nodes.Node
import org.openide.util.Lookup

/**
 * Selects a non-empty Kotlin package folder from a Projects-popup lookup.
 *
 * Only conventional Kotlin source and test trees are eligible. This keeps the Kotlin refactoring
 * provider from taking Java, resource, generated, or arbitrary filesystem folders that belong to
 * another NetBeans rename implementation.
 */
object KotlinProjectFolderRenameSelection {

    /**
     * Resolves one eligible conventional-Kotlin folder from [lookup].
     *
     * @param lookup Projects popup lookup containing exactly one selected [Node]
     * @return non-empty directory selection, or `null` when the lookup is not a supported Kotlin
     *         package folder
     */
    fun resolve(lookup: Lookup): KotlinMoveSourceSelection? {
        val folder = KotlinFolderNodeSelection.resolve(lookup.lookupAll(Node::class.java).toTypedArray())
            ?: return null
        val project = FileOwnerQuery.getOwner(folder) ?: return null
        return resolve(folder, KotlinProjectSourceGroups.findRoots(project.projectDirectory))
    }

    /**
     * Resolves [lookup] against explicitly supplied roots.
     *
     * This overload isolates the folder/root predicate for unit testing without a loaded project
     * owner. Production code must use [resolve] so root discovery remains project-scoped.
     *
     * @param lookup Projects popup lookup containing exactly one selected [Node]
     * @param roots conventional Kotlin roots belonging to the selected folder's project
     * @return non-empty directory selection, or `null` when not below [roots]
     */
    fun resolve(lookup: Lookup, roots: Collection<KotlinProjectSourceRoot>): KotlinMoveSourceSelection? {
        val folder = KotlinFolderNodeSelection.resolve(lookup.lookupAll(Node::class.java).toTypedArray())
            ?: return null
        return resolve(folder, roots)
    }

    /**
     * Resolves [folder] against explicitly supplied roots.
     *
     * @param folder physical package folder selected by the user
     * @param roots conventional Kotlin roots belonging to the selected folder's project
     * @return non-empty directory selection, or `null` when [folder] is outside [roots]
     */
    fun resolve(folder: FileObject, roots: Collection<KotlinProjectSourceRoot>): KotlinMoveSourceSelection? {
        if (roots.none { root -> folder == root.folder || FileUtil.isParentOf(root.folder, folder) }) {
            return null
        }
        return KotlinMoveSourceSelection.from(folder)?.takeIf(KotlinMoveSourceSelection::isDirectory)
    }

    /**
     * Chooses the initial directory name from an optional inline Explorer rename request.
     *
     * @param folder selected physical Kotlin package directory
     * @param requestedName name entered through inline Explorer rename, if any
     * @return nonblank requested name, otherwise the current simple folder name
     */
    fun initialName(folder: FileObject, requestedName: String?): String =
        requestedName?.takeIf(String::isNotBlank) ?: folder.name
}

