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
import io.github.nbplugins.kotlin.refactoring.ConstantDestination
import io.github.nbplugins.kotlin.refactoring.KaIntroduceConstantComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Ctrl+Alt+C** — "Introduce Constant" for Kotlin compile-time constant expressions.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin`.
 *
 * The action validates that the expression at the caret (or selection) is a compile-time constant
 * via [KaIntroduceConstantComputer] before opening the dialog. If the expression is not a constant
 * the action silently does nothing.
 */
class KotlinIntroduceConstantAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Introduce Constant...")
        putValue(SHORT_DESCRIPTION, "Introduce Constant")
        putValue(POPUP_MENU_TEXT, "Introduce Constant...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val selStart = target.selectionStart
        val selEnd = target.selectionEnd
        val startOffset = if (selStart < selEnd) selStart else target.caretPosition
        val endOffset = if (selStart < selEnd) selEnd else target.caretPosition

        runCatching {
            val (names, destinations, applicable) = resolveOutcome(doc, startOffset, endOffset)
            if (!applicable) return@runCatching
            val refactoring = KotlinIntroduceConstantRefactoring(doc, startOffset, endOffset)
            UI.openRefactoringUI(
                KotlinIntroduceConstantUI(names, destinations, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinIntroduceConstantAction failed", e)
        }
    }

    /**
     * Runs a quick analysis pass and returns suggested names, available destinations, and
     * whether the expression at [[startOffset]..[endOffset]] is a compile-time constant.
     *
     * @return triple of (suggestedNames, destinations, isApplicable)
     */
    private fun resolveOutcome(
        doc: StyledDocument,
        startOffset: Int,
        endOffset: Int,
    ): Triple<List<String>, List<ConstantDestination>, Boolean> {
        val fo = ProjectUtils.getFileObjectForDocument(doc)
            ?: return Triple(emptyList(), emptyList(), false)
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return Triple(emptyList(), emptyList(), false)
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path)
                ?: return@runCatching Triple(emptyList<String>(), emptyList<ConstantDestination>(), false)
            val computer = KaIntroduceConstantComputer(ktFile, startOffset, endOffset, session.session.project)
            when (val outcome = computer.compute()) {
                is KaIntroduceConstantComputer.Outcome.Ready ->
                    Triple(outcome.result.suggestedNames, outcome.result.destinations, true)
                else -> Triple(emptyList(), emptyList(), false)
            }
        }.getOrElse { Triple(emptyList(), emptyList(), false) }
    }

    companion object {
        /** Action name used in layer.xml and keybindings registration. */
        const val ACTION_NAME = "kotlin-introduce-constant"
    }
}
