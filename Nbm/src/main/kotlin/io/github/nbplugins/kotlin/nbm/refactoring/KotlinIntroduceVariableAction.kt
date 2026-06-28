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
import io.github.nbplugins.kotlin.refactoring.KaIntroduceVariableComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Ctrl+Alt+V** — "Introduce Variable" for Kotlin expressions.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin`.
 *
 * If the editor has an active selection the selected text defines the expression range; otherwise
 * the expression is the innermost [org.jetbrains.kotlin.psi.KtExpression] at the caret.
 *
 * The action performs a quick analysis via [KaIntroduceVariableComputer] to obtain name
 * suggestions before opening the dialog, so the dialog's name field is pre-filled.
 */
class KotlinIntroduceVariableAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Introduce Variable...")
        putValue(SHORT_DESCRIPTION, "Introduce Variable")
        putValue(POPUP_MENU_TEXT, "Introduce Variable...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val selStart = target.selectionStart
        val selEnd = target.selectionEnd
        val startOffset = if (selStart < selEnd) selStart else target.caretPosition
        val endOffset = if (selStart < selEnd) selEnd else target.caretPosition

        runCatching {
            val (names, applicable) = resolveOutcome(doc, startOffset, endOffset)
            if (!applicable) return@runCatching  // not on an extractable expression — stay silent
            val refactoring = KotlinIntroduceVariableRefactoring(doc, startOffset, endOffset)
            UI.openRefactoringUI(
                KotlinIntroduceVariableUI(names, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinIntroduceVariableAction failed", e)
        }
    }

    /**
     * Runs a quick analysis pass and returns the name suggestions plus whether the expression at
     * [[startOffset]..[endOffset]] is extractable.
     *
     * @return pair of (suggestedNames, isApplicable); names may be empty on errors
     */
    private fun resolveOutcome(doc: StyledDocument, startOffset: Int, endOffset: Int): Pair<List<String>, Boolean> {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return emptyList<String>() to false
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return emptyList<String>() to false
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching emptyList<String>() to false
            val computer = KaIntroduceVariableComputer(ktFile, startOffset, endOffset, session.session.project)
            when (val outcome = computer.compute()) {
                is KaIntroduceVariableComputer.Outcome.Ready -> outcome.result.suggestedNames to true
                else -> emptyList<String>() to false
            }
        }.getOrElse { emptyList<String>() to false }
    }

    companion object {
        /** Action name used in layer.xml and keybindings registration. */
        const val ACTION_NAME = "kotlin-introduce-variable"
    }
}
