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

import javax.swing.event.ChangeListener
import org.netbeans.api.project.Project
import org.netbeans.api.project.SourceGroup
import org.netbeans.api.project.Sources

/**
 * Contributes conventional Kotlin roots to a project's merged [Sources] lookup.
 *
 * @param project project whose `src/main/kotlin` and `src/test/kotlin` folders are exposed
 */
class KotlinProjectSources(private val project: Project) : Sources {

    /** Sources detected during the last query, used to report structural root changes. */
    private var roots: List<KotlinProjectSourceRoot> = emptyList()

    /** Listeners notified when conventional Kotlin roots appear or disappear. */
    private val listeners = mutableSetOf<ChangeListener>()

    /**
     * Returns Kotlin source groups for the requested [type].
     *
     * @param type source-group type requested by a NetBeans client
     * @return main or test Kotlin roots for the plugin's Kotlin source types, otherwise no roots
     */
    override fun getSourceGroups(type: String): Array<SourceGroup> {
        val currentRoots = KotlinProjectSourceGroups.findRoots(project.projectDirectory)
        if (currentRoots != roots) {
            roots = currentRoots
            notifyRootChange()
        }
        return when (type) {
            KotlinProjectSourceGroups.KOTLIN_SOURCE_TYPE ->
                KotlinProjectSourceGroups.createSourceGroups(currentRoots.filterNot(KotlinProjectSourceRoot::test))
            KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_TYPE ->
                KotlinProjectSourceGroups.createSourceGroups(currentRoots.filter(KotlinProjectSourceRoot::test))
            else -> emptyArray()
        }
    }

    /** Registers [listener] for changes in the conventional Kotlin root set. */
    override fun addChangeListener(listener: ChangeListener?) {
        if (listener != null) listeners += listener
    }

    /** Removes [listener] from conventional Kotlin root-set notifications. */
    override fun removeChangeListener(listener: ChangeListener?) {
        if (listener != null) listeners -= listener
    }

    /** Notifies Explorer clients after the root set changes. */
    private fun notifyRootChange() {
        val event = javax.swing.event.ChangeEvent(this)
        listeners.toList().forEach { it.stateChanged(event) }
    }
}
