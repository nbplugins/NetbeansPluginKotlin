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
import org.openide.loaders.DataFolder
import org.openide.loaders.DataObject
import org.openide.nodes.Node

/** Resolves a single folder selected through a physical or package-tree NetBeans node. */
object KotlinFolderNodeSelection {

    /**
     * Resolves exactly one valid physical folder from selected nodes.
     *
     * Package-tree nodes wrap a [DataFolder], while physical explorer nodes can expose a direct
     * [FileObject]. The ordered fallbacks keep folder refactorings independent of either view.
     *
     * @param nodes NetBeans nodes selected by the user
     * @return the selected valid folder, or `null` unless exactly one folder was selected
     */
    fun resolve(nodes: Array<Node>): FileObject? {
        if (nodes.size != 1) return null
        val lookup = nodes.single().lookup
        val file = lookup.lookup(FileObject::class.java)
            ?: lookup.lookup(DataFolder::class.java)?.primaryFile
            ?: lookup.lookup(DataObject::class.java)?.primaryFile
        return file?.takeIf { it.isValid && it.isFolder }
    }
}
