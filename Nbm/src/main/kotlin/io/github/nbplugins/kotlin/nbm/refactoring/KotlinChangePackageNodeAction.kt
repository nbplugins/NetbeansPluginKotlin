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
import org.openide.DialogDisplayer
import org.openide.NotifyDescriptor
import org.openide.awt.ActionID
import org.openide.awt.ActionRegistration
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.nodes.Node
import org.openide.util.HelpCtx
import org.openide.util.actions.NodeAction
import org.openide.windows.TopComponent
import javax.swing.SwingUtilities

/**
 * Refactor-menu action that changes package directives for one selected Kotlin file or for the
 * recursive Kotlin descendants of one selected folder.
 *
 * The Project tree reports a folder node while the editor reports the edited file node, so a single
 * `Menu/Refactoring` entry serves both contexts. The selected folder itself is not included in the
 * target package because physical directories do not move; descendant folders become suffixes of
 * the user-selected package. When the editor caret sits on an `import` directive, the entry targets
 * the imported package instead of the edited file.
 */
@ActionID(category = "Refactoring", id = "io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangePackageNodeAction")
@ActionRegistration(displayName = "Change Kotlin Package...")
open class KotlinChangePackageNodeAction : NodeAction() {
    init {
        putValue(NAME, "Change Kotlin Package...")
        putValue(SHORT_DESCRIPTION, "Change Package of Kotlin Files or a Directory")
    }

    /**
     * Prepares and opens the shared UI for the source under the node or editor caret.
     *
     * When the caret sits on an `import` directive, the operation targets the imported package
     * instead of the edited file's own package.
     *
     * @param nodes file or folder node supplied by NetBeans
     */
    override fun performAction(nodes: Array<Node>) {
        val source = selectedSource(nodes) ?: return
        val selection = KotlinChangePackageSourceSelection.from(source) ?: return
        runCatching {
            val project = ProjectUtils.getKotlinProjectForFileObject(selection.representativeFile)
                ?: ProjectUtils.getValidProject()
                ?: error("Change Kotlin Package could not resolve the Kotlin project.")
            val packageTarget = KotlinPackageTarget(project, selection.representativeFile)
            when (val importTarget = importTargetAtCaret(source, packageTarget)) {
                is KotlinImportPackageTarget.Declared -> openImportedPackage(importTarget, packageTarget)
                is KotlinImportPackageTarget.External -> showExternalPackageMessage(importTarget)
                null -> openFileOrFolderPackage(source, selection, packageTarget)
            }
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinChangePackageNodeAction failed", error) }
    }

    /** Opens the configured UI for a folder or single file selection. */
    private fun openFileOrFolderPackage(
        source: FileObject,
        selection: KotlinChangePackageSourceSelection,
        packageTarget: KotlinPackageTarget,
    ) {
        // A folder starts from its own source-root-relative package; one file starts from the
        // package it already declares.
        val initialPackage = if (selection.isDirectory) {
            packageTarget.packageForFolder(source)
        } else {
            packageTarget.defaultPackage
        }
        openRefactoringUi(
            KotlinChangePackageUI(
                KotlinChangePackageRefactoring(selection),
                packageTarget,
                initialPackage,
            ),
        )
    }

    /** Opens the configured UI for every project source that declares an imported package. */
    private fun openImportedPackage(
        target: KotlinImportPackageTarget.Declared,
        packageTarget: KotlinPackageTarget,
    ) {
        val selection = KotlinChangePackageSourceSelection.fromPackage(target.files, target.packageName) ?: return
        openRefactoringUi(
            KotlinChangePackageUI(
                KotlinChangePackageRefactoring(selection),
                packageTarget,
                target.packageName,
            ),
        )
    }

    /** Explains why an import of a package without project sources cannot start a package change. */
    private fun showExternalPackageMessage(target: KotlinImportPackageTarget.External) {
        val message = if (target.packageName.isEmpty()) {
            "The imported declaration's package is not declared by Kotlin sources in this project, " +
                "so there is no package to change."
        } else {
            "Kotlin package '${target.packageName}' is not declared by sources in this project, " +
                "so there is no package to change."
        }
        DialogDisplayer.getDefault().notifyLater(
            NotifyDescriptor.Message("Change Kotlin Package: $message", NotifyDescriptor.INFORMATION_MESSAGE),
        )
    }

    /**
     * Resolves the imported package when the caret of this file's active editor sits on `import`.
     *
     * @param file file selected by the node action
     * @param packageTarget project source roots and package validation
     * @return the caret's import target, or `null` when the caret is not on an import directive
     */
    private fun importTargetAtCaret(file: FileObject, packageTarget: KotlinPackageTarget): KotlinImportPackageTarget? {
        if (file.isFolder) return null
        val snapshot = editorSnapshot(file) ?: return null
        return importTargetAt(snapshot.text, snapshot.caretOffset, packageTarget::filesInPackage)
    }

    /**
     * Captures the caret and buffer of the editor that is currently showing [file].
     *
     * Reading Swing state off the Event Dispatch Thread is not safe, and the action itself runs in
     * a background worker, so the capture is marshalled onto the Event Dispatch Thread.
     *
     * @param file file whose active editor should be captured
     * @return caret and text, or `null` when [file] is not in the active editor
     */
    private fun editorSnapshot(file: FileObject): EditorSnapshot? {
        var snapshot: EditorSnapshot? = null
        val capture = {
            // A tree selection must keep file semantics, so only the active editor counts.
            val activated = TopComponent.getRegistry().activated
            if (activated?.lookup?.lookup(FileObject::class.java) == file) {
                val panes = runCatching {
                    DataObject.find(file).lookup.lookup(EditorCookie::class.java)?.openedPanes
                }.getOrNull()
                // A split editor can show the same file twice; prefer its active pane.
                val pane = panes?.firstOrNull { pane ->
                    SwingUtilities.getAncestorOfClass(TopComponent::class.java, pane) === activated
                } ?: panes?.firstOrNull()
                val document = pane?.document
                if (pane != null && document != null) {
                    snapshot = EditorSnapshot(pane.caretPosition, document.getText(0, document.length))
                }
            }
        }
        runCatching {
            if (EventQueue.isDispatchThread()) capture() else EventQueue.invokeAndWait(capture)
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinChangePackageNodeAction could not read the editor caret", error) }
        return snapshot
    }

    /** Caret position and buffer text captured from one active editor. */
    private data class EditorSnapshot(val caretOffset: Int, val text: CharSequence)

    /**
     * Opens the Swing refactoring UI on the Event Dispatch Thread.
     *
     * @param ui prepared UI whose display must run on the Event Dispatch Thread
     */
    internal fun openRefactoringUi(ui: KotlinChangePackageUI) {
        EventQueue.invokeLater {
            runCatching {
                UI.openRefactoringUI(ui, TopComponent.getRegistry().activated)
            }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinChangePackageNodeAction UI opening failed", error) }
        }
    }

    /**
     * Enables the entry for one folder containing Kotlin sources or for one Kotlin file.
     *
     * An editor window exposes its edited file as the active node, which lets the same Refactor
     * entry work inside a Kotlin file as well as in the Projects tree.
     */
    override fun enable(nodes: Array<Node>): Boolean =
        selectedFolder(nodes)?.let(KotlinChangePackageSourceSelection::from)?.isDirectory == true ||
            fileOf(nodes) != null

    /** Allows selection and project preparation to run off the Event Dispatch Thread. */
    override fun asynchronous(): Boolean = true

    /** @return NetBeans default help context. */
    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    /** @return stable action name used by the manual layer registration. */
    override fun getName(): String = "Change Kotlin Package"

    /** Resolves exactly one physical or package-tree folder node. */
    private fun selectedFolder(nodes: Array<Node>) = KotlinFolderNodeSelection.resolve(nodes)

    companion object {
        /**
         * Resolves the file or folder whose package directives the action may change.
         *
         * @param nodes selected or active NetBeans nodes
         * @return exactly one Kotlin file or one folder, or `null` for any other selection
         */
        internal fun selectedSource(nodes: Array<Node>): FileObject? =
            KotlinFolderNodeSelection.resolve(nodes) ?: fileOf(nodes)

        /**
         * Resolves the package named by the import directive on the caret's line.
         *
         * @param text current editor document text
         * @param caretOffset caret position inside [text]
         * @param filesInPackage finds Kotlin files that declare a given package
         * @return the caret's import target, or `null` when the caret is not on an import directive
         */
        internal fun importTargetAt(
            text: CharSequence,
            caretOffset: Int,
            filesInPackage: (String) -> List<FileObject>,
        ): KotlinImportPackageTarget? {
            val directive = KotlinImportDirectiveParser.findAt(text, caretOffset) ?: return null
            return KotlinImportPackageTarget.resolve(directive.importPath, directive.allUnder, filesInPackage)
        }

        /** Resolves one valid Kotlin file from a single selected node. */
        private fun fileOf(nodes: Array<Node>): FileObject? {
            if (nodes.size != 1) return null
            val lookup = nodes.single().lookup
            val file = lookup.lookup(FileObject::class.java) ?: lookup.lookup(DataObject::class.java)?.primaryFile
            return file?.takeIf { it.isValid && !it.isFolder && it.ext == "kt" }
        }
    }
}
