/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.navigation

import utils.KotlinTestCase

/**
 * Unit tests for [KotlinInspectHierarchyAction].
 *
 * Verifies instantiation and action name contract. Full integration testing (opening the
 * hierarchy window) requires a live NetBeans environment and is covered by manual testing.
 */
class KotlinInspectHierarchyActionTest : KotlinTestCase("KotlinInspectHierarchyAction", "navigation") {

    /**
     * Verifies that [KotlinInspectHierarchyAction] can be instantiated without error.
     */
    fun testActionInstantiable() {
        val action = KotlinInspectHierarchyAction()
        assertNotNull("KotlinInspectHierarchyAction must be instantiable", action)
    }

    /**
     * Verifies that [KotlinInspectHierarchyAction.ACTION_NAME] equals the expected
     * layer.xml registration key.
     */
    fun testActionName() {
        assertEquals(
            "Action name must match layer.xml registration key",
            "kotlin-inspect-hierarchy",
            KotlinInspectHierarchyAction.ACTION_NAME
        )
    }
}
