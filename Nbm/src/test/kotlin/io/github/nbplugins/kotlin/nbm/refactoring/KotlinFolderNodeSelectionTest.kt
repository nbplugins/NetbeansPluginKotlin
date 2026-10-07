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

import org.netbeans.junit.NbTestCase
import java.beans.PropertyChangeListener
import javax.swing.Icon
import org.netbeans.api.project.SourceGroup
import org.netbeans.spi.java.project.support.ui.PackageView
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileSystem
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataFolder
import org.openide.nodes.AbstractNode
import org.openide.nodes.Children
import org.openide.nodes.Node
import org.openide.util.lookup.Lookups

/** Unit tests for [KotlinFolderNodeSelection]. */
class KotlinFolderNodeSelectionTest : NbTestCase("KotlinFolderNodeSelectionTest") {

    /** In-memory filesystem used to model physical folder/file node selections. */
    private val fileSystem: FileSystem = FileUtil.createMemoryFileSystem()

    /** Verifies a physical folder exposed directly by a node is selected. */
    fun testResolve_usesDirectFolderFileObject() {
        val folder = fileSystem.root.createFolder("direct")

        assertEquals(folder, KotlinFolderNodeSelection.resolve(arrayOf(node(folder))))
    }

    /** Verifies package-shaped nodes exposing a DataFolder resolve to the physical package folder. */
    fun testResolve_usesDataFolderFromPackageNodeLookup() {
        val folder = fileSystem.root.createFolder("package")
        val packageNode = node(DataFolder.findFolder(folder))

        assertEquals(folder, KotlinFolderNodeSelection.resolve(arrayOf(packageNode)))
    }

    /** Verifies files and multi-selection do not accidentally become folder operations. */
    fun testResolve_rejectsFilesAndMultipleNodes() {
        val folder = fileSystem.root.createFolder("folder")
        val file = fileSystem.root.createData("Source.kt")

        assertNull(KotlinFolderNodeSelection.resolve(arrayOf(node(file))))
        assertNull(KotlinFolderNodeSelection.resolve(arrayOf(node(folder), node(folder))))
    }

    /** Verifies the real PackageView package-node lookup retains Kotlin directory selection. */
    fun testResolve_usesRealPackageViewNodeAndDirectoryCarrierPrecheck() {
        val sourceRoot = fileSystem.root.createFolder("src")
        val packageFolder = sourceRoot.createFolder("example")
        packageFolder.createData("Sample.kt")
        val sourceGroup = TestSourceGroup(sourceRoot)
        val packageRoot = PackageView.createPackageView(sourceGroup)
        val packageNode = PackageView.findPath(packageRoot, packageFolder)

        assertNotNull("PackageView must locate the physical package folder", packageNode)
        val folder = KotlinFolderNodeSelection.resolve(arrayOf(packageNode!!))
        assertEquals(packageFolder, folder)
        val selection = KotlinMoveSourceSelection.from(folder!!)
        assertNotNull("PackageView folder must discover its Kotlin source", selection)
        assertTrue(selection!!.isDirectory)

        val refactoring = KotlinRenameDirectoryRefactoring(packageFolder, "renamed", selection)
        val ui = KotlinFilesystemRenameUI(refactoring, true, packageFolder.name)
        ui.getPanel { }
        assertNull("Filesystem rename UI must accept the captured package selection", ui.setParameters())

        val problem = KotlinRenameDirectoryPlugin(refactoring).preCheck()
        assertNull("Captured package selection must remain usable through the rename lifecycle", problem)
    }

    /** Creates a leaf node whose lookup exposes [value]. */
    private fun node(value: Any): Node = AbstractNode(Children.LEAF, Lookups.singleton(value))

    /** Minimal source group used to obtain a genuine NetBeans package-tree node. */
    private class TestSourceGroup(private val root: FileObject) : SourceGroup {
        override fun getRootFolder(): FileObject = root
        override fun getName(): String = "test-source-root"
        override fun getDisplayName(): String = "Test Source Root"
        override fun getIcon(opened: Boolean): Icon? = null
        override fun contains(file: FileObject): Boolean = file == root || file.path.startsWith("${root.path}/")
        override fun addPropertyChangeListener(listener: PropertyChangeListener?) = Unit
        override fun removePropertyChangeListener(listener: PropertyChangeListener?) = Unit
    }
}
