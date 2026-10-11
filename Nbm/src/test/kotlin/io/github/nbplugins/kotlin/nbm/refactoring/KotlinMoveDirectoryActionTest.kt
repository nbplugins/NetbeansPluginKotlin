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

/** Unit tests for [KotlinMoveDirectoryAction]. */
class KotlinMoveDirectoryActionTest : NbTestCase("KotlinMoveDirectoryActionTest") {

    /** Verifies the legacy NodeAction dispatcher contract is explicitly synchronous. */
    fun testAsynchronous_isExplicitlyFalse() {
        assertFalse(
            "The directory action must declare its dispatcher contract to NetBeans",
            TestAction().isAsynchronous(),
        )
    }

    /** Exposes NodeAction's protected dispatcher contract for this focused test. */
    private class TestAction : KotlinMoveDirectoryAction() {
        fun isAsynchronous(): Boolean = asynchronous()
    }
}
