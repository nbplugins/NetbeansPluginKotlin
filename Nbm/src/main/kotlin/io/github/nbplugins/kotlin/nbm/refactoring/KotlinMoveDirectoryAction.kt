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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaMoveFileComputer
import java.awt.EventQueue
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.awt.ActionID
import org.openide.awt.ActionRegistration
import org.openide.nodes.Node
import org.openide.util.HelpCtx
import org.openide.util.RequestProcessor
import org.openide.util.actions.NodeAction
import org.openide.windows.TopComponent

/**
 * Folder-node action that moves all Kotlin descendants while retaining the selected folder below
 * the target package.
 *
 * The action deliberately accepts one folder only. It leaves non-Kotlin descendants in place and
 * delegates the Kotlin-only multi-file operation to [KotlinMoveFilePlugin].
 */
@ActionID(category = "Refactoring", id = "io.github.nbplugins.kotlin.nbm.refactoring.KotlinMoveDirectoryAction")
@ActionRegistration(displayName = "Move Kotlin Directory...")
open class KotlinMoveDirectoryAction : NodeAction() {

    init {
        putValue(NAME, "Move Kotlin Directory...")
        putValue(SHORT_DESCRIPTION, "Move Kotlin Files in Directory to Another Package")
    }

    /**
     * Opens the shared destination UI for the selected Kotlin-containing directory.
     *
     * Source discovery and K2 session preparation may take time, so NetBeans can run this action in
     * a worker. [openRefactoringUi] moves the Swing-only UI step to the Event Dispatch Thread.
     */
    override fun performAction(nodes: Array<Node>) {
        val folder = selectedFolder(nodes) ?: return
        REQUEST_PROCESSOR.post {
            val selection = KotlinMoveSourceSelection.from(folder) ?: return@post
            runCatching {
                val project = ProjectUtils.getKotlinProjectForFileObject(selection.representativeFile)
                    ?: ProjectUtils.getValidProject()
                    ?: return@runCatching
                val file = KotlinAnalysisAPISession.getSession(project).getKtFileForPath(selection.representativeFile.path)
                    ?: return@runCatching
                val result = (KaMoveFileComputer(file).compute() as? KaMoveFileComputer.Outcome.Ready)?.result
                    ?: return@runCatching
                val refactoring = KotlinMoveFileRefactoring(selection)
                val target = KotlinPackageTarget(project, selection.representativeFile)
                openRefactoringUi(
                    KotlinMoveFileUI(result, refactoring, target, target.packageForFolder(folder.parent ?: folder)),
                )
            }.onFailure { error ->
                KotlinLogger.INSTANCE.logException("KotlinMoveDirectoryAction failed", error)
            }
        }
    }

    /**
     * Opens a refactoring panel on the Event Dispatch Thread regardless of NodeAction's dispatcher.
     *
     * @param ui prepared destination UI; its construction is thread-neutral but its display is not
     */
    internal fun openRefactoringUi(ui: KotlinMoveFileUI) {
        KotlinLogger.INSTANCE.logInfo("KotlinMoveDirectoryAction: scheduling refactoring UI on EDT")
        EventQueue.invokeLater {
            runCatching {
                KotlinLogger.INSTANCE.logInfo("KotlinMoveDirectoryAction: opening refactoring UI on EDT")
                UI.openRefactoringUI(ui, TopComponent.getRegistry().activated)
            }.onFailure { error ->
                KotlinLogger.INSTANCE.logException("KotlinMoveDirectoryAction UI opening failed", error)
            }
        }
    }

    /** Enables the action exclusively for one physical folder containing a Kotlin descendant. */
    override fun enable(nodes: Array<Node>): Boolean = selectedFolder(nodes)
        ?.let(KotlinMoveSourceSelection::from)
        ?.isDirectory == true

    /** Declares the legacy action dispatcher contract; UI work is explicitly marshalled to EDT. */
    override fun asynchronous(): Boolean = false

    /** @return no dedicated help page. */
    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    /** @return stable action name used by the manual layer registration. */
    override fun getName(): String = "Move Kotlin Directory"

    /** Resolves exactly one physical or package-tree folder node. */
    private fun selectedFolder(nodes: Array<Node>) = KotlinFolderNodeSelection.resolve(nodes)

    private companion object {
        /** Worker used for source discovery and K2 preparation before the Swing UI is shown. */
        val REQUEST_PROCESSOR = RequestProcessor(KotlinMoveDirectoryAction::class.java.name, 1)
    }
}
