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

/** Delegates a source group while replacing its Project Explorer display name. */
class NamedSourceGroup(private val delegate: SourceGroup, private val displayName: String) : SourceGroup {

    /** Returns the delegated root folder. */
    override fun getRootFolder(): FileObject = delegate.rootFolder

    /** Returns the delegated stable source-group identifier. */
    override fun getName(): String = delegate.name

    /** Returns the supplied Project Explorer label. */
    override fun getDisplayName(): String = displayName

    /** Returns the delegated icon. */
    override fun getIcon(opened: Boolean): Icon? = delegate.getIcon(opened)

    /** Tests whether [file] is in the delegated source group. */
    override fun contains(file: FileObject): Boolean = delegate.contains(file)

    /** Registers [listener] with the delegated source group. */
    override fun addPropertyChangeListener(listener: PropertyChangeListener?) {
        delegate.addPropertyChangeListener(listener)
    }

    /** Removes [listener] from the delegated source group. */
    override fun removePropertyChangeListener(listener: PropertyChangeListener?) {
        delegate.removePropertyChangeListener(listener)
    }
}
