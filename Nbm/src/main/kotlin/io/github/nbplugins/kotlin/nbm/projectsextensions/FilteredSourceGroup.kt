/**
 * ******************************************************************************
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
 * ******************************************************************************
 */
package io.github.nbplugins.kotlin.nbm.projectsextensions

import java.beans.PropertyChangeListener
import javax.swing.Icon
import org.netbeans.api.project.SourceGroup
import org.openide.filesystems.FileObject

/** Hides [excludedFolders] from a broader Java source group without changing project metadata. */
class FilteredSourceGroup(
    private val delegate: SourceGroup,
    excludedFolders: Collection<FileObject>
) : SourceGroup {
    private val excludedPaths = excludedFolders.map(FileObject::getPath).toSet()

    /** Returns the delegated root folder. */
    override fun getRootFolder(): FileObject = delegate.rootFolder

    /** Returns the delegated stable source-group identifier. */
    override fun getName(): String = delegate.name

    /** Returns the delegated Project Explorer label. */
    override fun getDisplayName(): String = delegate.displayName

    /** Returns the delegated icon. */
    override fun getIcon(opened: Boolean): Icon? = delegate.getIcon(opened)

    /** Returns whether [file] belongs to the Java group and is not a Kotlin root or its descendant. */
    override fun contains(file: FileObject): Boolean =
        delegate.contains(file) && excludedPaths.none { path -> file.path == path || file.path.startsWith("$path/") }

    /** Registers [listener] with the delegated group. */
    override fun addPropertyChangeListener(listener: PropertyChangeListener?) {
        delegate.addPropertyChangeListener(listener)
    }

    /** Removes [listener] from the delegated group. */
    override fun removePropertyChangeListener(listener: PropertyChangeListener?) {
        delegate.removePropertyChangeListener(listener)
    }
}
