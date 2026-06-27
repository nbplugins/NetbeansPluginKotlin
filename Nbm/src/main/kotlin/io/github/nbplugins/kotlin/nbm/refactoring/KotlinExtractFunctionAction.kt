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
import io.github.nbplugins.kotlin.refactoring.KaExtractFunctionComputer
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
 * Editor action for **Ctrl+Alt+M** — "Extract Function" for Kotlin selections.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin`.
 *
 * The action requires a non-empty editor selection that spans one or more statements or
 * expressions. It performs a quick analysis via [KaExtractFunctionComputer] to obtain
 * suggested function names before opening the dialog, so the dialog's name field is pre-filled.
 *
 * When no text is selected the action stays silent (does not show an error dialog).
 */
class KotlinExtractFunctionAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Extract Function...")
        putValue(SHORT_DESCRIPTION, "Extract Function")
        putValue(POPUP_MENU_TEXT, "Extract Function...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: run {
            KotlinLogger.INSTANCE.logInfo("KotlinExtractFunctionAction: doc is not a StyledDocument")
            return
        }
        val selStart = target.selectionStart
        val selEnd = target.selectionEnd
        KotlinLogger.INSTANCE.logInfo("KotlinExtractFunctionAction: selStart=$selStart selEnd=$selEnd")
        if (selStart >= selEnd) {
            KotlinLogger.INSTANCE.logInfo("KotlinExtractFunctionAction: no selection — silent exit")
            return
        }

        runCatching {
            val resolved = resolveOutcome(doc, selStart, selEnd)
            KotlinLogger.INSTANCE.logInfo(
                "KotlinExtractFunctionAction: applicable=${resolved.applicable} " +
                "names=${resolved.names} scopes=${resolved.scopeCandidates.map { it.label }}"
            )
            if (!resolved.applicable) return@runCatching
            val refactoring = KotlinExtractFunctionRefactoring(doc, selStart, selEnd)
            refactoring.scopeCandidates = resolved.scopeCandidates
            UI.openRefactoringUI(
                KotlinExtractFunctionUI(resolved.names, resolved.scopeCandidates, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinExtractFunctionAction failed", e)
        }
    }

    /** Result of a quick analysis pass before the dialog opens. */
    private data class ResolvedOutcome(
        val names: List<String>,
        val scopeCandidates: List<ScopeCandidate>,
        val applicable: Boolean,
    )

    /**
     * Runs a quick analysis pass and returns name suggestions, scope candidates, and whether the
     * selection at [[startOffset]..[endOffset]] is extractable.
     */
    private fun resolveOutcome(doc: StyledDocument, startOffset: Int, endOffset: Int): ResolvedOutcome {
        val notApplicable = ResolvedOutcome(emptyList(), emptyList(), false)
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: run {
            KotlinLogger.INSTANCE.logInfo("KotlinExtractFunctionAction: fo=null")
            return notApplicable
        }
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: run {
                KotlinLogger.INSTANCE.logInfo("KotlinExtractFunctionAction: project=null for fo=${fo.path}")
                return notApplicable
            }
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: run {
                KotlinLogger.INSTANCE.logInfo("KotlinExtractFunctionAction: ktFile=null for ${fo.path}")
                return@runCatching notApplicable
            }
            val computer = KaExtractFunctionComputer(ktFile, startOffset, endOffset, session.session.project)
            val outcome = computer.compute()
            KotlinLogger.INSTANCE.logInfo("KotlinExtractFunctionAction: compute outcome=${outcome::class.simpleName}")
            when (outcome) {
                is KaExtractFunctionComputer.Outcome.Ready -> {
                    val candidates = computer.collectScopeCandidates()
                    ResolvedOutcome(outcome.result.suggestedNames, candidates, true)
                }
                is KaExtractFunctionComputer.Outcome.Error -> {
                    KotlinLogger.INSTANCE.logInfo("KotlinExtractFunctionAction: Error: ${outcome.error.message}")
                    notApplicable
                }
                else -> notApplicable
            }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException("KotlinExtractFunctionAction resolveOutcome failed", e)
            notApplicable
        }
    }

    companion object {
        /** Action name used in layer.xml and keybindings registration. */
        const val ACTION_NAME = "kotlin-extract-function"
    }
}
