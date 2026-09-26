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
 * Editor action that changes the active Kotlin file's package without moving its physical path.
 */
class KotlinChangePackageAction : BaseAction(ACTION_NAME, SAVE_POSITION or ABBREV_RESET) {
    init {
        putValue(NAME, "Change Kotlin Package...")
        putValue(SHORT_DESCRIPTION, "Change Kotlin File Package Without Moving It")
        putValue(POPUP_MENU_TEXT, "Change Kotlin Package...")
    }

    /**
     * Opens the package-change UI for the active Kotlin source file.
     *
     * @param evt action event raised by NetBeans
     * @param target active editor component
     */
    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        val document = target.document as? StyledDocument ?: return
        runCatching {
            val source = ProjectUtils.getFileObjectForDocument(document) ?: return@runCatching
            val project = ProjectUtils.getKotlinProjectForFileObject(source)
                ?: ProjectUtils.getValidProject()
                ?: return@runCatching
            val selection = KotlinChangePackageSourceSelection.from(source) ?: return@runCatching
            val packageTarget = KotlinPackageTarget(project, source)
            UI.openRefactoringUI(
                KotlinChangePackageUI(KotlinChangePackageRefactoring(selection), packageTarget, packageTarget.defaultPackage),
                TopComponent.getRegistry().activated,
            )
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinChangePackageAction failed", error) }
    }

    companion object {
        /** Action name used by the manual layer.xml registration. */
        const val ACTION_NAME = "kotlin-change-package"
    }
}
