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

import org.netbeans.editor.BaseAction
import org.netbeans.junit.NbTestCase

/**
 * Unit tests for [KotlinGoToTestAction].
 */
class KotlinGoToTestActionTest : NbTestCase("KotlinGoToTestActionTest") {

    fun testActionNameConstant() {
        assertEquals("kotlin-goto-test", KotlinGoToTestAction.ACTION_NAME)
    }

    fun testPopupMenuText() {
        val action = KotlinGoToTestAction()
        assertEquals("Go to Test/Tested class", action.getValue(BaseAction.POPUP_MENU_TEXT))
    }

    fun testShortDescription() {
        val action = KotlinGoToTestAction()
        assertEquals("Go to Test/Tested class", action.getValue(BaseAction.SHORT_DESCRIPTION))
    }
}
