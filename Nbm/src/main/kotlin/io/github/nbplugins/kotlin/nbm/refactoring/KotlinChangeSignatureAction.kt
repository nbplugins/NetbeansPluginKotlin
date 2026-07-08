/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureComputer
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureResult
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Change Signature** (Ctrl+F6) — renames/reorders/adds/removes parameters and
 * changes the return type of the function or constructor at the caret, retargeting every usage via
 * IDEA's real ported K2 Change Signature engine ([KaChangeSignatureComputer]).
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin` and exposed in the
 * Refactor menu.
 */
class KotlinChangeSignatureAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Change Signature...")
        putValue(SHORT_DESCRIPTION, "Change Method Signature")
        putValue(POPUP_MENU_TEXT, "Change Signature...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val caretOffset = target.caretPosition

        runCatching {
            val result = resolveOutcome(doc, caretOffset) ?: return@runCatching
            val refactoring = KotlinChangeSignatureRefactoring(doc, caretOffset)
            UI.openRefactoringUI(
                KotlinChangeSignatureUI(result, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinChangeSignatureAction failed", e)
        }
    }

    /**
     * Runs a quick analysis pass and returns the current signature, or `null` when the caret is
     * not on a function, constructor, or class with a primary constructor.
     */
    private fun resolveOutcome(doc: StyledDocument, caretOffset: Int): KaChangeSignatureResult? {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return null
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching null
            val computer = KaChangeSignatureComputer(ktFile, caretOffset)
            when (val outcome = computer.compute()) {
                is KaChangeSignatureComputer.Outcome.Ready -> outcome.result
                else -> null
            }
        }.getOrElse { null }
    }

    companion object {
        /** Action name used in layer.xml registration. */
        const val ACTION_NAME = "kotlin-change-signature"
    }
}
