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
 *******************************************************************************/
package org.jetbrains.kotlin.refactorings.rename

import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileUtil
import org.openide.nodes.AbstractNode
import org.openide.nodes.Children
import org.openide.nodes.Node
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups

/** Unit tests for [KotlinActionsImplementationProvider]'s node-context routing. */
class KotlinActionsImplementationProviderTest : NbTestCase("KotlinActionsImplementationProviderTest") {

    /** Verifies one Kotlin file node is the only physical-file rename selection accepted. */
    fun testSelectedKotlinFile_acceptsExactlyOneKotlinFileNode() {
        val root = FileUtil.createMemoryFileSystem().root
        val kotlinFile = root.createData("Sample.kt")
        val textFile = root.createData("notes.txt")

        assertEquals(kotlinFile, KotlinActionsImplementationProvider.selectedKotlinFile(Lookups.fixed(node(kotlinFile))))
        assertNull(KotlinActionsImplementationProvider.selectedKotlinFile(Lookups.fixed(node(textFile))))
        assertNull(
            KotlinActionsImplementationProvider.selectedKotlinFile(
                Lookups.fixed(node(kotlinFile), node(root.createData("Other.kt"))),
            ),
        )
    }

    /** Verifies any node selection blocks fallback to an unrelated open editor context. */
    fun testHasNodeSelection_detectsSingleAndMultipleExplorerNodes() {
        val root = FileUtil.createMemoryFileSystem().root
        val first = node(root.createData("First.kt"))
        val second = node(root.createData("Second.kt"))

        assertFalse(KotlinActionsImplementationProvider.hasNodeSelection(Lookup.EMPTY))
        assertTrue(KotlinActionsImplementationProvider.hasNodeSelection(Lookups.fixed(first)))
        assertTrue(KotlinActionsImplementationProvider.hasNodeSelection(Lookups.fixed(first, second)))
    }

    /** Creates a file-tree node exposing the supplied filesystem object. */
    private fun node(value: Any): Node = AbstractNode(Children.LEAF, Lookups.singleton(value))
}
