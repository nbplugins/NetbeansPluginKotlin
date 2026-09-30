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
import io.github.nbplugins.kotlin.refactoring.KaMoveNestedMemberComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/** Opens the target-selection UI for the portable K2 Move Nested Member subset. */
class KotlinMoveNestedMemberAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {
    init {
        putValue(NAME, "Move Nested Member...")
        putValue(SHORT_DESCRIPTION, "Move Nested Kotlin Class or Companion Member")
        putValue(POPUP_MENU_TEXT, "Move Nested Member...")
    }

    /** Resolves the source declaration and controller-owned target candidates before opening the view. */
    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val document = target.document as? StyledDocument ?: return
        runCatching {
            val source = ProjectUtils.getFileObjectForDocument(document)
                ?: error("Move Nested Member requires a saved Kotlin source file.")
            val project = ProjectUtils.getKotlinProjectForFileObject(source)
                ?: ProjectUtils.getValidProject()
                ?: error("Move Nested Member could not resolve the Kotlin project.")
            val session = KotlinAnalysisAPISession.getSession(project)
            val sourcePsi = session.getKtFileForPath(source.path)
                ?: error("Move Nested Member could not resolve the Kotlin source file.")
            val computer = KaMoveNestedMemberComputer(sourcePsi, target.caretPosition)
            val ready = computer.compute() as? KaMoveNestedMemberComputer.Outcome.Ready
                ?: error("Move Nested Member is not available at this caret location.")
            val candidates = computer.discoverTargets(
                session.fileMap.keys.mapNotNull(session::getKtFileForPath),
            )
            if (candidates.isEmpty()) error("Move Nested Member found no compatible target class or object.")
            val refactoring = KotlinMoveNestedMemberRefactoring(document, target.caretPosition)
            UI.openRefactoringUI(
                KotlinMoveNestedMemberUI(ready, candidates, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { KotlinLogger.INSTANCE.logException("Move Nested Member action failed", it) }
    }

    companion object {
        /** Layer action identifier. */
        const val ACTION_NAME = "kotlin-move-nested-member"
    }
}
