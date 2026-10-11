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
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.nodes.Node
import org.openide.util.HelpCtx
import org.openide.util.RequestProcessor
import org.openide.util.actions.NodeAction
import org.openide.windows.TopComponent

/** Opens the explicit filesystem-rename UI for a Kotlin file selected in a project tree. */
@ActionID(category = "Refactoring", id = "io.github.nbplugins.kotlin.nbm.refactoring.KotlinRenameFileNodeAction")
@ActionRegistration(displayName = "Rename Kotlin File...")
open class KotlinRenameFileNodeAction : NodeAction() {

    init {
        putValue(NAME, "Rename Kotlin File...")
        putValue(SHORT_DESCRIPTION, "Rename Kotlin File")
    }

    /**
     * Opens the file-rename UI for the selected Kotlin file.
     *
     * @param nodes selected NetBeans nodes
     */
    override fun performAction(nodes: Array<Node>) {
        val file = selectedFile(nodes) ?: return
        REQUEST_PROCESSOR.post {
            EventQueue.invokeLater {
                runCatching {
                    UI.openRefactoringUI(
                        KotlinFilesystemRenameUI(KotlinRenameFileRefactoring(file, file.name), false, file.nameExt),
                        TopComponent.getRegistry().activated,
                    )
                }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinRenameFileNodeAction failed", error) }
            }
        }
    }

    /** @return true only for one valid physical Kotlin source file. */
    override fun enable(nodes: Array<Node>): Boolean = selectedFile(nodes) != null

    /** Declares the legacy action dispatcher contract; UI work is explicitly marshalled to EDT. */
    override fun asynchronous(): Boolean = false

    /** @return no dedicated help page. */
    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    /** @return stable name used by the manual layer registration. */
    override fun getName(): String = "Rename Kotlin File"

    /** Resolves one selected physical Kotlin file from NetBeans file-tree node lookups. */
    private fun selectedFile(nodes: Array<Node>): FileObject? {
        if (nodes.size != 1) return null
        val lookup = nodes.single().lookup
        val file = lookup.lookup(FileObject::class.java) ?: lookup.lookup(DataObject::class.java)?.primaryFile
        return file?.takeIf { it.isValid && !it.isFolder && it.ext == "kt" }
    }

    private companion object {
        /** Worker used to hand off the action without making Swing UI from the dispatcher thread. */
        val REQUEST_PROCESSOR = RequestProcessor(KotlinRenameFileNodeAction::class.java.name, 1)
    }
}
