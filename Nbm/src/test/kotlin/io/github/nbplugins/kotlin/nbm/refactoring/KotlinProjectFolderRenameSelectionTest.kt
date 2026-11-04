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

import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectSourceRoot
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataFolder
import org.openide.nodes.AbstractNode
import org.openide.nodes.Children
import org.openide.nodes.Node
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups

/** Unit tests for [KotlinProjectFolderRenameSelection]. */
class KotlinProjectFolderRenameSelectionTest : NbTestCase("KotlinProjectFolderRenameSelectionTest") {

    /** Verifies package descendants of conventional Kotlin roots are eligible but the roots are not. */
    fun testResolve_acceptsKotlinPackageDescendantsAndRejectsRoots() {
        val project = FileUtil.createMemoryFileSystem().root
        val mainRoot = FileUtil.createFolder(project, "src/main/kotlin")
        val mainPackage = FileUtil.createFolder(mainRoot, "sample/main")
        mainPackage.createData("Main.kt")
        val testRoot = FileUtil.createFolder(project, "src/test/kotlin")
        val testPackage = FileUtil.createFolder(testRoot, "sample/test")
        testPackage.createData("Test.kt")
        val roots = roots(mainRoot, testRoot)

        assertNull("generic Rename must not rename a source root", resolve(node(mainRoot), roots))
        assertNull("generic Rename must not rename a test source root", resolve(node(testRoot), roots))
        assertEquals(mainPackage, resolve(node(mainPackage), roots)?.selectedDirectory)
        assertEquals(testPackage, resolve(node(testPackage), roots)?.selectedDirectory)
    }

    /** Verifies folders outside conventional roots and path-prefix siblings cannot be Kotlin renames. */
    fun testResolve_rejectsNonKotlinRootsAndPrefixSiblings() {
        val project = FileUtil.createMemoryFileSystem().root
        val mainRoot = FileUtil.createFolder(project, "src/main/kotlin")
        val kotlinSibling = FileUtil.createFolder(project, "src/main/kotlin2")
        kotlinSibling.createData("Wrong.kt")
        val javaPackage = FileUtil.createFolder(project, "src/main/java/sample")
        javaPackage.createData("Java.kt")
        val resources = FileUtil.createFolder(project, "src/main/resources/sample")
        resources.createData("Resource.kt")
        val build = FileUtil.createFolder(project, "build/generated/sample")
        build.createData("Generated.kt")
        val roots = roots(mainRoot)

        assertNull(resolve(node(kotlinSibling), roots))
        assertNull(resolve(node(javaPackage), roots))
        assertNull(resolve(node(resources), roots))
        assertNull(resolve(node(build), roots))
    }

    /** Verifies only folders with Kotlin descendants from exactly one supported node are eligible. */
    fun testResolve_rejectsEmptyNonKotlinAndMultipleSelections() {
        val project = FileUtil.createMemoryFileSystem().root
        val mainRoot = FileUtil.createFolder(project, "src/main/kotlin")
        val empty = mainRoot.createFolder("empty")
        val resources = mainRoot.createFolder("resources")
        resources.createData("config.json")
        val packageFolder = mainRoot.createFolder("sample")
        packageFolder.createData("Sample.kt")
        val roots = roots(mainRoot)

        assertNull(resolve(node(empty), roots))
        assertNull(resolve(node(resources), roots))
        assertNull(KotlinProjectFolderRenameSelection.resolve(Lookups.fixed(node(packageFolder), node(packageFolder)), roots))
    }

    /** Verifies inline Explorer names replace only the directory UI's initial name. */
    fun testInitialName_prefersNonBlankExplorerName() {
        val folder = FileUtil.createMemoryFileSystem().root.createFolder("original")

        assertEquals("renamed", KotlinProjectFolderRenameSelection.initialName(folder, "renamed"))
        assertEquals("original", KotlinProjectFolderRenameSelection.initialName(folder, ""))
        assertEquals("original", KotlinProjectFolderRenameSelection.initialName(folder, "   "))
        assertEquals("original", KotlinProjectFolderRenameSelection.initialName(folder, null))
    }

    /** Verifies package-tree DataFolder nodes use the same folder and root eligibility rules. */
    fun testResolve_acceptsDataFolderPackageNode() {
        val project = FileUtil.createMemoryFileSystem().root
        val mainRoot = FileUtil.createFolder(project, "src/main/kotlin")
        val packageFolder = FileUtil.createFolder(mainRoot, "sample/package")
        packageFolder.createData("Sample.kt")

        val selection = resolve(node(DataFolder.findFolder(packageFolder)), roots(mainRoot))

        assertNotNull(selection)
        assertEquals(packageFolder, selection!!.selectedDirectory)
        assertEquals(listOf("Sample.kt"), selection.sourceFiles.map(FileObject::getNameExt))
    }

    /** Resolves a one-node context using explicitly supplied conventional roots. */
    private fun resolve(node: Node, roots: List<KotlinProjectSourceRoot>): KotlinMoveSourceSelection? =
        KotlinProjectFolderRenameSelection.resolve(Lookups.singleton(node), roots)

    /** Creates main/test root descriptors without requiring a loaded NetBeans project. */
    private fun roots(vararg folders: FileObject): List<KotlinProjectSourceRoot> =
        folders.mapIndexed { index, folder -> KotlinProjectSourceRoot(folder, index > 0) }

    /** Creates a physical or package-tree node carrying [value]. */
    private fun node(value: Any): Node = AbstractNode(Children.LEAF, Lookups.singleton(value))
}
