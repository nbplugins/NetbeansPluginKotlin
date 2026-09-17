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

import java.awt.EventQueue
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.awt.ActionID
import org.openide.awt.ActionRegistration
import org.openide.loaders.DataObject
import org.openide.nodes.Node
import org.openide.util.HelpCtx
import org.openide.util.actions.NodeAction
import org.openide.windows.TopComponent

/**
 * Folder-node action that changes package directives for recursive Kotlin descendants in place.
 *
 * The selected folder itself is not included in the target package because physical directories do
 * not move. Descendant folders become suffixes of the user-selected package.
 */
@ActionID(category = "Refactoring", id = "io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangePackageDirectoryAction")
@ActionRegistration(displayName = "Change Kotlin Package...")
open class KotlinChangePackageDirectoryAction : NodeAction() {
    init {
        putValue(NAME, "Change Kotlin Package...")
        putValue(SHORT_DESCRIPTION, "Change Package of Kotlin Files in Directory")
    }

    /**
     * Prepares and opens the shared UI for a selected Kotlin-containing folder.
     *
     * @param nodes folder node supplied by NetBeans
     */
    override fun performAction(nodes: Array<Node>) {
        val folder = selectedFolder(nodes) ?: return
        val selection = KotlinChangePackageSourceSelection.from(folder) ?: return
        runCatching {
            val project = ProjectUtils.getKotlinProjectForFileObject(selection.representativeFile)
                ?: ProjectUtils.getValidProject()
                ?: return@runCatching
            val packageTarget = KotlinPackageTarget(project, selection.representativeFile)
            openRefactoringUi(
                KotlinChangePackageUI(
                    KotlinChangePackageRefactoring(selection),
                    packageTarget,
                    packageTarget.packageForFolder(folder),
                ),
            )
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinChangePackageDirectoryAction failed", error) }
    }

    /**
     * Opens the Swing refactoring UI on the Event Dispatch Thread.
     *
     * @param ui prepared UI whose display must run on the Event Dispatch Thread
     */
    internal fun openRefactoringUi(ui: KotlinChangePackageUI) {
        EventQueue.invokeLater {
            runCatching { UI.openRefactoringUI(ui, TopComponent.getRegistry().activated) }
                .onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinChangePackageDirectoryAction UI opening failed", error) }
        }
    }

    /** @return true only for one valid folder containing at least one Kotlin descendant. */
    override fun enable(nodes: Array<Node>): Boolean = selectedFolder(nodes)
        ?.let(KotlinChangePackageSourceSelection::from)
        ?.isDirectory == true

    /** @return NetBeans default help context. */
    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    /** @return stable action name used by the manual layer registration. */
    override fun getName(): String = "Change Kotlin Package"

    /** Resolves exactly one physical folder from NetBeans' selected node. */
    private fun selectedFolder(nodes: Array<Node>): org.openide.filesystems.FileObject? {
        if (nodes.size != 1) return null
        val dataObject = nodes.single().lookup.lookup(DataObject::class.java) ?: return null
        return dataObject.primaryFile.takeIf { it.isValid && it.isFolder }
    }
}
