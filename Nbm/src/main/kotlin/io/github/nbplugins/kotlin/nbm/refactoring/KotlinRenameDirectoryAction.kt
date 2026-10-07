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
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.awt.ActionID
import org.openide.awt.ActionRegistration
import org.openide.nodes.Node
import org.openide.util.HelpCtx
import org.openide.util.RequestProcessor
import org.openide.util.actions.NodeAction
import org.openide.windows.TopComponent

/**
 * Folder-node action that renames one Kotlin package directory and its physical descendants.
 *
 * The refactoring transaction performs one physical folder rename while preserving every descendant.
 * Use Change Kotlin Package for package directives and supported Kotlin-reference updates.
 */
@ActionID(category = "Refactoring", id = "io.github.nbplugins.kotlin.nbm.refactoring.KotlinRenameDirectoryAction")
@ActionRegistration(displayName = "Rename Kotlin Package Directory...")
open class KotlinRenameDirectoryAction : NodeAction() {

    init {
        putValue(NAME, "Rename Kotlin Package Directory...")
        putValue(SHORT_DESCRIPTION, "Rename Kotlin Package Directory")
    }

    /**
     * Opens the package-directory rename UI for one folder containing Kotlin descendants.
     *
     * @param nodes selected NetBeans nodes
     */
    override fun performAction(nodes: Array<Node>) {
        val selection = selectedSelection(nodes) ?: return
        val folder = selection.selectedDirectory ?: return
        REQUEST_PROCESSOR.post {
            EventQueue.invokeLater {
                runCatching {
                    UI.openRefactoringUI(
                        KotlinFilesystemRenameUI(
                            KotlinRenameDirectoryRefactoring(folder, folder.name, selection),
                            true,
                            folder.name,
                        ),
                        TopComponent.getRegistry().activated,
                    )
                }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinRenameDirectoryAction failed", error) }
            }
        }
    }

    /** Enables the action for exactly one physical folder containing at least one Kotlin file. */
    override fun enable(nodes: Array<Node>): Boolean = selectedSelection(nodes) != null

    /** Declares the legacy action dispatcher contract; UI work is explicitly marshalled to EDT. */
    override fun asynchronous(): Boolean = false

    /** @return no dedicated help page. */
    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    /** @return stable name used by the manual layer registration. */
    override fun getName(): String = "Rename Kotlin Package Directory"

    /** Resolves the non-empty Kotlin selection validated before the refactoring UI is opened. */
    private fun selectedSelection(nodes: Array<Node>): KotlinMoveSourceSelection? = KotlinFolderNodeSelection.resolve(nodes)
        ?.let(KotlinMoveSourceSelection::from)
        ?.takeIf(KotlinMoveSourceSelection::isDirectory)

    private companion object {
        /** Worker used for filesystem discovery before the Swing refactoring UI is shown. */
        val REQUEST_PROCESSOR = RequestProcessor(KotlinRenameDirectoryAction::class.java.name, 1)
    }
}
