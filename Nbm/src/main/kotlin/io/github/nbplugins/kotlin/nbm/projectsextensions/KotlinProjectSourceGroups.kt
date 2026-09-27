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

/** A conventional Kotlin source root and its Project Explorer role. */
data class KotlinProjectSourceRoot(val folder: FileObject, val test: Boolean) {

    /** The label displayed for this root in Project Explorer. */
    val displayName: String
        get() = if (test) KotlinProjectSourceGroups.KOTLIN_TEST_SOURCE_PACKAGES
        else KotlinProjectSourceGroups.KOTLIN_SOURCE_PACKAGES
}

/**
 * Locates conventional Kotlin source roots and creates source groups for Project Explorer.
 *
 * This deliberately treats only `src/main/kotlin` and `src/test/kotlin` as Kotlin roots.
 * Arbitrary source directories configured by a build tool remain visible to K2 analysis via
 * the existing classpath providers, but are not guessed as Kotlin trees in the UI.
 */
object KotlinProjectSourceGroups {

    /** Sources type used to query main Kotlin roots from a project's [org.netbeans.api.project.Sources]. */
    const val KOTLIN_SOURCE_TYPE = "kotlin-main"

    /** Sources type used to query test Kotlin roots from a project's [org.netbeans.api.project.Sources]. */
    const val KOTLIN_TEST_SOURCE_TYPE = "kotlin-test"

    /** Project Explorer label for Kotlin production packages. */
    const val KOTLIN_SOURCE_PACKAGES = "Kotlin Source Packages"

    /** Project Explorer label for Kotlin test packages. */
    const val KOTLIN_TEST_SOURCE_PACKAGES = "Kotlin Test Packages"

    /**
     * Finds existing conventional Kotlin roots below [projectDirectory].
     *
     * @param projectDirectory root directory of a NetBeans project
     * @return existing main root first and test root second
     */
    fun findRoots(projectDirectory: FileObject): List<KotlinProjectSourceRoot> =
        listOf("src/main/kotlin" to false, "src/test/kotlin" to true)
            .mapNotNull { (path, test) ->
                projectDirectory.getFileObject(path)
                    ?.takeIf(FileObject::isFolder)
                    ?.let { KotlinProjectSourceRoot(it, test) }
            }

    /**
     * Creates de-duplicated source groups for [roots].
     *
     * @param roots Kotlin roots to expose in Project Explorer
     * @return one source group per distinct root folder
     */
    fun createSourceGroups(roots: Collection<KotlinProjectSourceRoot>): Array<SourceGroup> =
        roots.distinctBy { it.folder.path }
            .map(::KotlinProjectSourceGroup)
            .toTypedArray()
}

/** Source group wrapper giving a Kotlin root its dedicated Explorer label. */
private class KotlinProjectSourceGroup(private val root: KotlinProjectSourceRoot) : SourceGroup {

    /** Returns the Kotlin root folder. */
    override fun getRootFolder(): FileObject = root.folder

    /** Returns a stable source-group identifier. */
    override fun getName(): String = root.folder.path

    /** Returns the user-visible Kotlin package-tree label. */
    override fun getDisplayName(): String = root.displayName

    /** Returns the ordinary NetBeans source-root icon. */
    override fun getIcon(opened: Boolean): Icon? = null

    /** Tests whether [file] belongs to this source root. */
    override fun contains(file: FileObject): Boolean =
        file == root.folder || file.path.startsWith("${root.folder.path}/")

    /** This static root has no dynamic properties. */
    override fun addPropertyChangeListener(listener: PropertyChangeListener?) = Unit

    /** This static root has no dynamic properties. */
    override fun removePropertyChangeListener(listener: PropertyChangeListener?) = Unit
}
