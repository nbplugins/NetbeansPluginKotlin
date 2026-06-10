/*******************************************************************************
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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.options.formatter

import org.netbeans.junit.NbTestCase
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Tests for [OptionTreePanel].
 *
 * <p>Verifies that [BoolItem]/[WrapItem] lambdas are called correctly, and that
 * the tree model structure matches the supplied [OptionGroup] list.
 */
class OptionTreePanelTest : NbTestCase("OptionTreePanelTest") {

    /** BoolItem set/get lambdas round-trip through the backing variable. */
    fun testBoolItemToggleUpdatesValue() {
        var value = false
        val item = BoolItem("Test bool", { value }, { value = it })

        assertFalse(item.get())
        item.set(true)
        assertTrue(value)
        assertTrue(item.get())
        item.set(false)
        assertFalse(value)
    }

    /** WrapItem set/get lambdas round-trip through the backing variable. */
    fun testWrapItemSetUpdatesValue() {
        var value = 0
        val item = WrapItem("Test wrap", { value }, { value = it })

        assertEquals(0, item.get())
        item.set(1)
        assertEquals(1, value)
        assertEquals(1, item.get())
        item.set(5)
        assertEquals(5, value)
    }

    /** OptionTreePanel builds a JTree with one child node per OptionGroup. */
    fun testGroupNodeCount() {
        val groups = listOf(
            OptionGroup("G1", listOf(BoolItem("a", { true }, {}))),
            OptionGroup("G2", listOf(WrapItem("b", { 0 }, {}))),
        )
        val panel = OptionTreePanel(groups) {}
        val root = panel.tree.model.root as DefaultMutableTreeNode
        assertEquals(2, root.childCount)
        assertEquals("G1", root.getChildAt(0).toString())
        assertEquals("G2", root.getChildAt(1).toString())
    }
}
