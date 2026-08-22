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
import io.github.nbplugins.kotlin.refactoring.KaIntroduceTypeAliasComputer
import io.github.nbplugins.kotlin.refactoring.KaIntroduceTypeAliasResult
import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Ctrl+Alt+Shift+A** — "Introduce Type Alias" for Kotlin type references.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin`.
 *
 * The action validates the caret location, or a non-empty editor selection, via
 * [KaIntroduceTypeAliasComputer] before opening the dialog. The selection range is retained so
 * selecting a complete type extracts that concrete type, while selecting its constructor extracts
 * a generic alias.
 */
class KotlinIntroduceTypeAliasAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Introduce Type Alias...")
        putValue(SHORT_DESCRIPTION, "Introduce Type Alias")
        putValue(POPUP_MENU_TEXT, "Introduce Type Alias...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val selectionStart = target.selectionStart
        val selectionRange = TextRange(selectionStart, target.selectionEnd)
            .takeIf { !it.isEmpty }
        val triggerOffset = selectionRange?.startOffset ?: target.caretPosition

        runCatching {
            val result = resolveOutcome(doc, triggerOffset, selectionRange) ?: return@runCatching
            val refactoring = KotlinIntroduceTypeAliasRefactoring(doc, triggerOffset, selectionRange)
            UI.openRefactoringUI(
                KotlinIntroduceTypeAliasUI(result, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinIntroduceTypeAliasAction failed", e)
        }
    }

    /**
     * Runs a quick analysis pass and returns the initial result, or `null` when the caret is not
     * on a type reference.
     *
     * @return [KaIntroduceTypeAliasResult] if applicable, `null` otherwise
     */
    private fun resolveOutcome(
        doc: StyledDocument,
        caretOffset: Int,
        selectionRange: TextRange?,
    ): KaIntroduceTypeAliasResult? {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return null
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching null
            val computer = KaIntroduceTypeAliasComputer(ktFile, caretOffset, selectionRange)
            when (val outcome = computer.compute()) {
                is KaIntroduceTypeAliasComputer.Outcome.Ready -> outcome.result
                else -> null
            }
        }.getOrElse { null }
    }

    companion object {
        /** Action name used in layer.xml and keybindings registration. */
        const val ACTION_NAME = "kotlin-introduce-type-alias"
    }
}
