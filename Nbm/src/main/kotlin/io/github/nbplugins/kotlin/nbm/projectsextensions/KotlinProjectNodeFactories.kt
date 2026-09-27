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

import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import org.netbeans.api.java.project.JavaProjectConstants
import org.netbeans.api.project.Project
import org.netbeans.api.project.ProjectUtils
import org.netbeans.api.project.SourceGroup
import org.netbeans.api.project.Sources
import org.netbeans.spi.java.project.support.ui.PackageView
import org.netbeans.spi.project.ui.support.NodeFactory
import org.netbeans.spi.project.ui.support.NodeList
import org.openide.nodes.Node
import org.openide.util.ChangeSupport
import org.openide.util.RequestProcessor

/**
 * Adds dedicated Kotlin main and test package nodes to project types whose standard
 * node factory already renders Java source groups.
 */
class KotlinProjectNodeFactory : NodeFactory {

    /**
     * Creates the Kotlin package-node list for [project].
     *
     * @param project project being rendered in Project Explorer
     * @return dynamic list of Kotlin source-root nodes
     */
    override fun createNodes(project: Project): NodeList<SourceGroup> =
        KotlinSourceNodeList(project, includeJavaGroups = false, includeGradleOtherGroups = false, filterJavaKotlinRoots = false)
}

/** Supplies the missing source nodes for the legacy third-party Gradle integration. */
class KotlinLegacyGradleProjectNodeFactory : NodeFactory {

    /**
     * Creates a source list for the legacy Gradle project type.
     *
     * @param project legacy Gradle project being rendered in Project Explorer
     * @return Kotlin package-tree nodes for the project
     */
    override fun createNodes(project: Project): NodeList<SourceGroup> =
        KotlinSourceNodeList(project, includeJavaGroups = true, includeGradleOtherGroups = false, filterJavaKotlinRoots = true)
}

/**
 * Replaces the built-in Gradle source node factory, which otherwise merges Java and Kotlin trees.
 */
class KotlinGradleProjectNodeFactory : NodeFactory {

    /**
     * Creates a source list that preserves Java, resource, and generated nodes while splitting Kotlin.
     *
     * @param project Gradle project being rendered in Project Explorer
     * @return dynamic list of Gradle source-root nodes
     */
    override fun createNodes(project: Project): NodeList<SourceGroup> =
        KotlinSourceNodeList(project, includeJavaGroups = true, includeGradleOtherGroups = true, filterJavaKotlinRoots = false)
}

/** Replaces the J2SE Java node factory so broad Ant roots do not duplicate Kotlin folders. */
class KotlinJ2SEProjectNodeFactory : NodeFactory {

    /**
     * Creates a source list with Java package trees filtered to omit conventional Kotlin roots.
     *
     * @param project J2SE/Ant project being rendered in Project Explorer
     * @return dynamic list of separated Java and Kotlin source-root nodes
     */
    override fun createNodes(project: Project): NodeList<SourceGroup> =
        KotlinSourceNodeList(project, includeJavaGroups = true, includeGradleOtherGroups = false, filterJavaKotlinRoots = true)
}

/**
 * Produces PackageView nodes from standard and Kotlin source groups.
 *
 * The Gradle implementation uses public `Sources` type names deliberately: the Gradle Java
 * implementation owns those types but the Kotlin plugin must not take a hard module dependency on it.
 */
private class KotlinSourceNodeList(
    private val project: Project,
    private val includeJavaGroups: Boolean,
    private val includeGradleOtherGroups: Boolean,
    private val filterJavaKotlinRoots: Boolean
) : NodeList<SourceGroup>, ChangeListener {

    private val changes = ChangeSupport(this)

    /**
     * Returns ordered source groups for Project Explorer.
     *
     * @return Java roots when requested, followed by separated Kotlin roots and Gradle auxiliary roots
     */
    override fun keys(): List<SourceGroup> {
        val sources = ProjectUtils.getSources(project)
        val groups = mutableListOf<SourceGroup>()
        if (includeJavaGroups) {
            val kotlinRoots = KotlinProjectSourceGroups.findRoots(project.projectDirectory).map(KotlinProjectSourceRoot::folder)
            groups += sources.getSourceGroups(JavaProjectConstants.SOURCES_TYPE_JAVA)
                .map { group -> if (filterJavaKotlinRoots) FilteredSourceGroup(group, kotlinRoots) else group }
        }

        val projectKotlinGroups = sources.getSourceGroups(KotlinProjectSourceGroups.KOTLIN_SOURCE_TYPE) +
            sources.getSourceGroups(KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_TYPE)
        val kotlinGroupPaths = projectKotlinGroups.map { it.rootFolder.path }.toSet()
        val gradleKotlinGroups = sources.getSourceGroups("kotlin")
            .filterNot { it.rootFolder.path in kotlinGroupPaths }
        groups += gradleKotlinGroups.map(::labelKotlinGroup)
        groups += projectKotlinGroups

        if (includeGradleOtherGroups) {
            groups += sources.getSourceGroups(JavaProjectConstants.SOURCES_TYPE_RESOURCES)
            groups += sources.getSourceGroups("generated")
        }
        return groups.distinctBy { it.rootFolder.path }
    }

    /**
     * Creates a package-view node for [key].
     *
     * @param key source group to render
     * @return package tree rooted at [key]
     */
    override fun node(key: SourceGroup): Node = PackageView.createPackageView(key)

    /** Starts listening for source-model changes. */
    override fun addNotify() {
        ProjectUtils.getSources(project).addChangeListener(this)
    }

    /** Stops listening for source-model changes. */
    override fun removeNotify() {
        ProjectUtils.getSources(project).removeChangeListener(this)
    }

    /**
     * Schedules an Explorer refresh outside the source-model callback stack.
     *
     * @param event source-model change event
     */
    override fun stateChanged(event: ChangeEvent) {
        REFRESH_PROCESSOR.post { changes.fireChange() }
    }

    /**
     * Registers a [listener] for changes in source nodes.
     *
     * @param listener listener to register
     */
    override fun addChangeListener(listener: ChangeListener) {
        changes.addChangeListener(listener)
    }

    /**
     * Removes a [listener] from source-node changes.
     *
     * @param listener listener to remove
     */
    override fun removeChangeListener(listener: ChangeListener) {
        changes.removeChangeListener(listener)
    }

    /** Gives a Gradle Kotlin source group a stable language-specific package-tree label. */
    private fun labelKotlinGroup(group: SourceGroup): SourceGroup = when {
        group.rootFolder.path.endsWith("/src/main/kotlin") ->
            NamedSourceGroup(group, KotlinProjectSourceGroups.KOTLIN_SOURCE_PACKAGES)
        group.rootFolder.path.endsWith("/src/test/kotlin") ->
            NamedSourceGroup(group, KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_PACKAGES)
        else -> group
    }

    private companion object {
        val REFRESH_PROCESSOR = RequestProcessor(KotlinSourceNodeList::class.java)
    }
}
