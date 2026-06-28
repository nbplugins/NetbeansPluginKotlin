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
import io.github.nbplugins.kotlin.refactoring.KaIntroduceImportAliasComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action for **Refactor → Introduce Import Alias** for Kotlin files.
 *
 * Registered as a global action in `layer.xml` and added to `Menu/Refactoring/` at position 1075.
 * No keybinding is registered (matches IDEA behaviour where this refactoring has no default shortcut).
 *
 * Trigger points (ported from `KotlinIntroduceImportAliasHandler`):
 *  - Cursor on an `import pkg.Class` directive line
 *  - Cursor on any `KtNameReferenceExpression` in the file body (K2 resolves the FQN and
 *    locates the corresponding import directive automatically)
 *
 * If neither trigger point is applicable the action silently does nothing.
 */
class KotlinIntroduceImportAliasAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Introduce Import Alias...")
        putValue(SHORT_DESCRIPTION, "Introduce Import Alias")
        putValue(POPUP_MENU_TEXT, "Introduce Import Alias...")
    }

    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val doc = target.document as? StyledDocument ?: return
        val caretOffset = target.caretPosition

        runCatching {
            val shortName = resolveShortName(doc, caretOffset) ?: return@runCatching
            val refactoring = KotlinIntroduceImportAliasRefactoring(doc, caretOffset)
            UI.openRefactoringUI(
                KotlinIntroduceImportAliasUI(refactoring, shortName),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinIntroduceImportAliasAction failed", e)
        }
    }

    /**
     * Runs a quick analysis pass to check whether the caret is on a suitable trigger point and
     * returns the short name of the import that will be aliased.
     *
     * @return the short name (e.g. `"MyClass"`) to pre-fill the dialog, or `null` if not applicable
     */
    private fun resolveShortName(doc: StyledDocument, caretOffset: Int): String? {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return null
        return runCatching {
            val session = KotlinAnalysisAPISession.getSession(project)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching null
            val computer = KaIntroduceImportAliasComputer(ktFile, caretOffset)
            when (val outcome = computer.compute()) {
                is KaIntroduceImportAliasComputer.Outcome.Ready -> outcome.result.shortName
                else -> null
            }
        }.getOrElse { null }
    }

    companion object {
        /** Action name used in layer.xml registration. */
        const val ACTION_NAME = "kotlin-introduce-import-alias"
    }
}
