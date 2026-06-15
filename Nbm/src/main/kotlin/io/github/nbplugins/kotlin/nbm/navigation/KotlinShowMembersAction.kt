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
import org.netbeans.modules.navigator.NavigatorTC
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent

/**
 * Editor action for **Ctrl+Shift+F12** — "Inspect Members" for Kotlin files.
 *
 * Opens and focuses the Navigator window, which shows the structure of the current
 * Kotlin file via [KaStructureScanner]. Mirrors
 * `ShowMembersAtCaretAction` from `java.navigation` but without any Java-specific logic.
 *
 * Registered under action name `"kotlin-inspect-members"` in `layer.xml` for
 * `text/x-kotlin`.
 */
class KotlinShowMembersAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(SHORT_DESCRIPTION, "Inspect Members")
        putValue(POPUP_MENU_TEXT, "Inspect Members")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val navTC = NavigatorTC.getInstance()
        navTC.open()
        navTC.requestActive()
    }

    companion object {
        const val ACTION_NAME = "kotlin-inspect-members"
    }
}
