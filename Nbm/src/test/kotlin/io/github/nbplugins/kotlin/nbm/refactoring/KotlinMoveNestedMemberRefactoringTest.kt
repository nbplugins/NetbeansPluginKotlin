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
import javax.swing.text.DefaultStyledDocument

/** Tests selection validation in [KotlinMoveNestedMemberRefactoring]. */
class KotlinMoveNestedMemberRefactoringTest : NbTestCase("KotlinMoveNestedMemberRefactoringTest") {

    /** Returns `null` until the target file path and offset are both supplied. */
    fun testRequest_requiresCompleteTargetSelection() {
        val refactoring = KotlinMoveNestedMemberRefactoring(DefaultStyledDocument(), 12)

        assertNull(refactoring.request())
        refactoring.targetFilePath = "/project/Target.kt"
        assertNull(refactoring.request())
        refactoring.targetOffset = 25

        assertEquals(
            KotlinMoveNestedMemberTarget("/project/Target.kt", 25),
            refactoring.request(),
        )
    }

    /** Verifies the plugin lifecycle guards are available before NetBeans resolves project PSI. */
    fun testPlugin_lifecycleHooks_doNotThrow() {
        val plugin = KotlinMoveNestedMemberPlugin(KotlinMoveNestedMemberRefactoring(DefaultStyledDocument(), 0))

        assertNull(plugin.preCheck())
        assertNull(plugin.fastCheckParameters())
        assertNull(plugin.checkParameters())
        plugin.cancelRequest()
    }
}
