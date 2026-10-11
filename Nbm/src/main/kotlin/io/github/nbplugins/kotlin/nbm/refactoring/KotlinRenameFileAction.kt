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

import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.editor.BaseAction
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.windows.TopComponent
import java.awt.event.ActionEvent
import javax.swing.text.JTextComponent
import javax.swing.text.StyledDocument

/**
 * Editor action that renames only the physical basename of the active Kotlin source file.
 *
 * Package directives and references intentionally remain unchanged because Kotlin declarations do
 * not derive their fully qualified names from a source-file basename.
 */
class KotlinRenameFileAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {

    init {
        putValue(NAME, "Rename Kotlin File...")
        putValue(SHORT_DESCRIPTION, "Rename Kotlin File")
        putValue(POPUP_MENU_TEXT, "Rename Kotlin File...")
    }

    /**
     * Opens the explicit physical file-rename UI.
     *
     * @param evt NetBeans action event
     * @param target active Kotlin editor component
     */
    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val document = target.document as? StyledDocument ?: return
        runCatching {
            val file = ProjectUtils.getFileObjectForDocument(document)?.takeIf { it.isValid && it.ext == "kt" }
                ?: return@runCatching
            UI.openRefactoringUI(
                KotlinFilesystemRenameUI(KotlinRenameFileRefactoring(file, file.name), false, file.nameExt),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinRenameFileAction failed", error) }
    }

    companion object {
        /** Action name used by the manual layer.xml registration. */
        const val ACTION_NAME = "kotlin-rename-file"
    }
}
