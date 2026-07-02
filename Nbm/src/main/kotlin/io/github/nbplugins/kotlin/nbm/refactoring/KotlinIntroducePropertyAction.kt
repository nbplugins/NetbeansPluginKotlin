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
import io.github.nbplugins.kotlin.refactoring.KaIntroducePropertyComputer
import io.github.nbplugins.kotlin.refactoring.KaIntroducePropertyResult
import io.github.nbplugins.kotlin.refactoring.ScopeCandidate
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Ctrl+Alt+F** — "Introduce Property" for Kotlin expressions inside a class body
 * or at file scope.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin`.
 *
 * On invocation, the action runs a quick analysis pass via [KaIntroducePropertyComputer] to check
 * applicability and collect scope candidates.  If the expression is suitable, the refactoring UI
 * dialog is opened.
 */
class KotlinIntroducePropertyAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Introduce Property...")
        putValue(SHORT_DESCRIPTION, "Introduce Property")
        putValue(POPUP_MENU_TEXT, "Introduce Property...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val selStart = target.selectionStart
        val selEnd = target.selectionEnd
        val startOffset = if (selStart < selEnd) selStart else target.caretPosition
        val endOffset = if (selStart < selEnd) selEnd else target.caretPosition

        runCatching {
            val outcome = resolveOutcome(doc, startOffset, endOffset) ?: return@runCatching
            val (result, scopeCandidates) = outcome
            val refactoring = KotlinIntroducePropertyRefactoring(doc, startOffset, endOffset)
            UI.openRefactoringUI(
                KotlinIntroducePropertyUI(result, scopeCandidates, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinIntroducePropertyAction failed", e)
        }
    }

    /**
     * Runs a quick analysis pass and returns the initial result and scope candidates, or `null`
     * when the expression is not suitable for property introduction.
     *
     * @return pair of (KaIntroducePropertyResult, scopeCandidates), or `null` if not applicable
     */
    private fun resolveOutcome(
        doc: StyledDocument,
        startOffset: Int,
        endOffset: Int,
    ): Pair<KaIntroducePropertyResult, List<ScopeCandidate>>? {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return null
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching null
            val computer = KaIntroducePropertyComputer(ktFile, startOffset, endOffset, session.session.project)
            val scopeCandidates = computer.collectScopeCandidates()
            when (val outcome = computer.compute()) {
                is KaIntroducePropertyComputer.Outcome.Ready -> outcome.result to scopeCandidates
                else -> null
            }
        }.getOrElse { null }
    }

    companion object {
        /** Action name used in layer.xml and keybindings registration. */
        const val ACTION_NAME = "kotlin-introduce-property"
    }
}
