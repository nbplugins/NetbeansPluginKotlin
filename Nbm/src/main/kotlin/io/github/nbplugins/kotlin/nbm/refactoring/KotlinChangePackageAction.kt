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
        KotlinLogger.INSTANCE.logInfo(
            "KotlinChangePackageAction: constructed instance=${System.identityHashCode(this)} " +
                "action=$ACTION_NAME thread=${Thread.currentThread().name}",
        )
    }

    /**
     * Records that the final NetBeans BaseAction dispatcher accepted an editor target before it
     * updates editor state and invokes this action's Kotlin-specific callback.
     *
     * @param target editor component that passed BaseAction's target/document guard
     */
    override fun updateComponent(target: JTextComponent) {
        KotlinLogger.INSTANCE.logInfo(
            "KotlinChangePackageAction: BaseAction dispatch passed target guard " +
                "instance=${System.identityHashCode(this)} target=${target.javaClass.name} " +
                "document=${target.document.javaClass.name} thread=${Thread.currentThread().name}",
        )
        super.updateComponent(target)
    }

    /**
     * Opens the package-change UI for the active Kotlin source file.
     *
     * @param evt action event raised by NetBeans
     * @param target active editor component
     */
    override fun actionPerformed(evt: ActionEvent, target: JTextComponent) {
        KotlinLogger.INSTANCE.logInfo(
            "KotlinChangePackageAction: invoked instance=${System.identityHashCode(this)} " +
                "command=${evt.actionCommand} thread=${Thread.currentThread().name}",
        )
        val document = target.document as? StyledDocument
        if (document == null) {
            KotlinLogger.INSTANCE.logInfo("KotlinChangePackageAction: aborted because the editor document is not styled")
            return
        }
        runCatching {
            val source = ProjectUtils.getFileObjectForDocument(document)
            if (source == null) {
                KotlinLogger.INSTANCE.logInfo("KotlinChangePackageAction: aborted because the editor document has no file object")
                return@runCatching
            }
            val project = ProjectUtils.getKotlinProjectForFileObject(source) ?: ProjectUtils.getValidProject()
            if (project == null) {
                KotlinLogger.INSTANCE.logInfo("KotlinChangePackageAction: aborted because no Kotlin project was resolved")
                return@runCatching
            }
            val selection = KotlinChangePackageSourceSelection.from(source)
            if (selection == null) {
                KotlinLogger.INSTANCE.logInfo("KotlinChangePackageAction: aborted because the file is not a Kotlin package source")
                return@runCatching
            }
            val packageTarget = KotlinPackageTarget(project, source)
            KotlinLogger.INSTANCE.logInfo("KotlinChangePackageAction: context resolved")
            KotlinLogger.INSTANCE.logInfo("KotlinChangePackageAction: opening refactoring UI")
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
