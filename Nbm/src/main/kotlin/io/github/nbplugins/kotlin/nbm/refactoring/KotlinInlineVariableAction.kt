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

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Ctrl+Alt+N** — "Inline Variable" for Kotlin `val`/`var` declarations.
 *
 * Registered under action name [ACTION_NAME] in `layer.xml` for `text/x-kotlin`.
 * When triggered, builds a [KotlinInlineVariableRefactoring] carrying the document and caret
 * offset, and opens the NetBeans refactoring UI which dispatches to [KotlinInlineVariablePlugin].
 *
 * The action does **no validation** on its own — the plugin's `prepare()` validates and surfaces
 * a fatal `Problem` if the cursor is not on an inlineable property. This keeps the action thin and
 * lets the framework display IDEA's error messages verbatim.
 *
 * This class belongs to the **controller** layer.
 */
class KotlinInlineVariableAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        // NAME drives both the Refactor submenu label (Menu/Refactoring shadow) and the
        // command palette; without it the menu shows the bare action-name "kotlin-inline-variable".
        putValue(NAME, "Inline Variable")
        putValue(SHORT_DESCRIPTION, "Inline Variable")
        putValue(POPUP_MENU_TEXT, "Inline Variable")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val caretOffset = target.caretPosition

        runCatching {
            // Resolve the property name for the dialog title. Don't fail the action when the
            // cursor isn't on a property — the plugin returns a clear Problem in that case.
            val name = resolvePropertyNameAt(doc, caretOffset) ?: ""
            val refactoring = KotlinInlineVariableRefactoring(doc, caretOffset)
            UI.openRefactoringUI(
                KotlinInlineVariableUI(name, refactoring),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinInlineVariableAction failed", e)
        }
    }

    /**
     * Looks up the `KtProperty` at [offset] in [doc] and returns its short name for the dialog
     * title, or `null` when the caret is not on a property (the framework then shows a generic
     * "Inline Variable" title).
     *
     * @param doc      the document under the caret
     * @param offset   caret offset within [doc]
     * @return         the property's simple name, or `null`
     */
    private fun resolvePropertyNameAt(doc: StyledDocument, offset: Int): String? {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return null
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching null
            val element = ktFile.findElementAt(offset) ?: return@runCatching null
            PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)?.name
        }.getOrNull()
    }

    companion object {
        /** Action name used in layer.xml and keybindings registration. */
        const val ACTION_NAME = "kotlin-inline-variable"
    }
}
