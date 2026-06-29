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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaCopyDeclarationComputer
import io.github.nbplugins.kotlin.refactoring.KaCopyDeclarationResult
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Copy Declaration** — copies the top-level declaration at the caret to a new file.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin` and exposed in the
 * Refactor menu at position 250.
 *
 * The action validates that the caret is inside a top-level named declaration via
 * [KaCopyDeclarationComputer] before opening the dialog.
 */
class KotlinCopyDeclarationAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Copy Declaration...")
        putValue(SHORT_DESCRIPTION, "Copy Declaration to New File")
        putValue(POPUP_MENU_TEXT, "Copy Declaration...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val caretOffset = target.caretPosition

        runCatching {
            val result = resolveOutcome(doc, caretOffset) ?: return@runCatching
            val refactoring = KotlinCopyDeclarationRefactoring(doc, caretOffset)
            UI.openRefactoringUI(
                KotlinCopyDeclarationUI(result, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinCopyDeclarationAction failed", e)
        }
    }

    /**
     * Runs a quick analysis pass and returns the initial result, or `null` when the caret is not
     * inside a top-level named declaration.
     *
     * @return [KaCopyDeclarationResult] if applicable, `null` otherwise
     */
    private fun resolveOutcome(doc: StyledDocument, caretOffset: Int): KaCopyDeclarationResult? {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return null
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching null
            val computer = KaCopyDeclarationComputer(ktFile, caretOffset)
            when (val outcome = computer.compute()) {
                is KaCopyDeclarationComputer.Outcome.Ready -> outcome.result
                else -> null
            }
        }.getOrElse { null }
    }

    companion object {
        /** Action name used in layer.xml registration. */
        const val ACTION_NAME = "kotlin-copy-declaration"
    }
}
