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
package io.github.nbplugins.kotlin.nbm.options

import org.junit.Test
import org.netbeans.junit.NbTestCase

/**
 * Tests for [KotlinOptionsPanelController].
 */
class KotlinOptionsPanelControllerTest : NbTestCase("KotlinOptionsPanelControllerTest") {

    private val controller = KotlinOptionsPanelController()

    /** Controller can be instantiated with no-arg constructor. */
    @Test
    fun testInstantiation() {
        assertNotNull(controller)
    }

    /** Panel is always valid (no mandatory fields). */
    @Test
    fun testIsValid() {
        assertTrue(controller.isValid)
    }

    /** Not changed before any interaction. */
    @Test
    fun testNotChangedInitially() {
        assertFalse(controller.isChanged)
    }

    /** cancel() keeps changed as false. */
    @Test
    fun testCancelKeepsNotChanged() {
        controller.cancel()
        assertFalse(controller.isChanged)
    }

    /** Property-change listeners can be added and removed without error. */
    @Test
    fun testPropertyChangeListeners() {
        val listener = java.beans.PropertyChangeListener { }
        controller.addPropertyChangeListener(listener)
        controller.removePropertyChangeListener(listener)
    }

    /** getHelpCtx returns non-null. */
    @Test
    fun testHelpCtx() {
        assertNotNull(controller.helpCtx)
    }
}
