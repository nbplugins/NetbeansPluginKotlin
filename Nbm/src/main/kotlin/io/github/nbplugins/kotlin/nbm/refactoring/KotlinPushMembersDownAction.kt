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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaPushMembersDownComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/** Opens the Kotlin Push Members Down member-selection refactoring UI. */
class KotlinPushMembersDownAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {
    init {
        putValue(NAME, "Push Members Down...")
        putValue(SHORT_DESCRIPTION, "Push Members Down")
        putValue(POPUP_MENU_TEXT, "Push Members Down...")
    }

    /** Resolves K2 candidates at the active editor caret and opens the NetBeans refactoring UI. */
    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val document = target.document as? StyledDocument ?: return
        runCatching {
            val source = ProjectUtils.getFileObjectForDocument(document)
                ?: error("Push Members Down requires a saved Kotlin source file.")
            val project = ProjectUtils.getKotlinProjectForFileObject(source)
                ?: ProjectUtils.getValidProject()
                ?: error("Push Members Down could not resolve the Kotlin project.")
            val session = KotlinAnalysisAPISession.getBuildScopedSession(project)
            val psi = session.getKtFileForPath(source.path)
                ?: error("Push Members Down could not resolve the Kotlin source file.")
            val discovery = KaPushMembersDownComputer(psi, target.caretPosition).discover()
                as? KaPushMembersDownComputer.Discovery.Ready
                ?: error("Push Members Down is not available at this caret location.")
            val refactoring = KotlinPushMembersDownRefactoring(document, target.caretPosition).apply {
                sourceOffset = discovery.sourceOffset
            }
            UI.openRefactoringUI(KotlinPushMembersDownUI(discovery, refactoring), TopComponent.getRegistry().activated)
        }.onFailure { KotlinLogger.INSTANCE.logException("Push Members Down action failed", it) }
    }

    companion object {
        /** Layer action identifier. */
        const val ACTION_NAME = "kotlin-push-members-down"
    }
}
