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
import io.github.nbplugins.kotlin.refactoring.KaMoveFileComputer
import io.github.nbplugins.kotlin.refactoring.KaMoveFileResult
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for moving the currently open Kotlin file to another source-root/package directory.
 *
 * The physical move, package update, and supported Kotlin reference retargeting are executed later
 * by [KotlinMoveFilePlugin] through the NetBeans refactoring lifecycle.
 */
class KotlinMoveFileAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Move Kotlin File...")
        putValue(SHORT_DESCRIPTION, "Move Kotlin File to Another Package")
        putValue(POPUP_MENU_TEXT, "Move Kotlin File...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val document = target.document as? StyledDocument ?: return
        runCatching {
            val source = ProjectUtils.getFileObjectForDocument(document) ?: return@runCatching
            val project = ProjectUtils.getKotlinProjectForFileObject(source)
                ?: ProjectUtils.getValidProject()
                ?: return@runCatching
            val selection = KotlinMoveSourceSelection.from(source) ?: return@runCatching
            val result = resolveOutcome(project, source.path) ?: return@runCatching
            val refactoring = KotlinMoveFileRefactoring(selection)
            UI.openRefactoringUI(
                KotlinMoveFileUI(result, refactoring, KotlinPackageTarget(project, selection.representativeFile)),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinMoveFileAction failed", error)
        }
    }

    /** Resolves a discovery result for the current physical Kotlin source file. */
    private fun resolveOutcome(project: org.netbeans.api.project.Project, sourcePath: String): KaMoveFileResult? {
        val session = KotlinAnalysisAPISession.getSession(project)
        val file = session.getKtFileForPath(sourcePath) ?: return null
        return (KaMoveFileComputer(file).compute() as? KaMoveFileComputer.Outcome.Ready)?.result
    }

    companion object {
        /** Action name used by the manual layer.xml registration. */
        const val ACTION_NAME = "kotlin-move-file"
    }
}
