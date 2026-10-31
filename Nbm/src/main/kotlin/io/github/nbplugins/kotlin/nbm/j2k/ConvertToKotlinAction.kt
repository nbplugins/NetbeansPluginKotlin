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
package io.github.nbplugins.kotlin.nbm.j2k

import org.jetbrains.kotlin.log.KotlinLogger
import org.netbeans.api.project.FileOwnerQuery
import org.netbeans.modules.refactoring.spi.ui.UI
import org.openide.DialogDisplayer
import org.openide.NotifyDescriptor
import org.openide.awt.ActionID
import org.openide.awt.ActionRegistration
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.nodes.Node
import org.openide.util.HelpCtx
import org.openide.util.ImageUtilities
import org.openide.util.Utilities
import org.openide.util.actions.NodeAction
import org.openide.windows.TopComponent

/**
 * **Refactor → Convert to Kotlin** — converts the selected Java source file into a sibling
 * Kotlin file with the IDEA K2 J2K engine (see [Java2KotlinConverter]).
 *
 * The action is registered once, under [ACTION_NAME], in `Actions/Refactoring`, and referenced
 * from `Menu/Refactoring`; that single folder feeds all three places NetBeans shows refactorings:
 * the **Refactor** main menu, the Refactor submenu of the Java editor's right-click menu, and the
 * Refactor submenu of the Java file node in the Projects/Files windows.
 *
 * It is enabled only when the current selection (or, when the editor has no node in its lookup,
 * the global context) is exactly one `text/x-java` file; the file must also belong to a project,
 * which is checked when the action runs.
 *
 * Invoking the action opens the standard NetBeans Refactoring window ([JavaToKotlinUI]); the
 * conversion itself runs only after the user presses **Refactor**, under NetBeans' own progress
 * dialog. An unconvertible target (missing file, existing `.kt` sibling) is rejected with an error
 * dialog before the window opens: a parameterless refactoring never shows a parameters dialog, so
 * a problem reported later would be silently dropped.
 *
 * This is the **controller** layer: it resolves and validates the target, while the conversion
 * itself is performed by [JavaToKotlinPlugin].
 */
@ActionID(category = "Refactoring", id = "io.github.nbplugins.kotlin.nbm.j2k.ConvertToKotlinAction")
@ActionRegistration(displayName = "Convert to Kotlin", iconBase = "org/jetbrains/kotlin/kotlin.png")
open class ConvertToKotlinAction : NodeAction() {

    init {
        putValue(NAME, JavaToKotlinUI.NAME)
        putValue(SHORT_DESCRIPTION, "Convert the Java Source File to Kotlin")
        putValue(SMALL_ICON, ImageUtilities.loadImageIcon(KOTLIN_ICON_PATH, false))
    }

    /** Opens the Refactoring window for the selected Java file. */
    override fun performAction(nodes: Array<Node>) {
        val javaFile = targetFile(nodes) ?: return
        val project = FileOwnerQuery.getOwner(javaFile)
        if (project == null) {
            KotlinLogger.INSTANCE.logInfo(
                "ConvertToKotlinAction: ${javaFile.path} does not belong to a project",
            )
            return
        }
        val refactoring = JavaToKotlinRefactoring(project, javaFile)
        val problem = refactoring.validate()
        if (problem != null) {
            DialogDisplayer.getDefault().notify(
                NotifyDescriptor.Message(
                    problem.message ?: "Java to Kotlin conversion is not applicable.",
                    NotifyDescriptor.ERROR_MESSAGE,
                ),
            )
            return
        }
        // Must run on the EDT: the Refactoring window builds its Swing components immediately.
        UI.openRefactoringUI(JavaToKotlinUI(refactoring), TopComponent.getRegistry().activated)
    }

    /**
     * Enables the action for a single Java source file.
     *
     * Widened to `public` (from the protected `NodeAction` hook) so tests can assert the
     * resolution without a live window system.
     */
    public override fun enable(nodes: Array<Node>): Boolean = targetFile(nodes) != null

    /** Declares the legacy action contract; all UI work happens on the EDT. */
    override fun asynchronous(): Boolean = false

    /** @return no dedicated help page. */
    override fun getHelpCtx(): HelpCtx = HelpCtx.DEFAULT_HELP

    /** @return stable name used by the manual layer registration. */
    override fun getName(): String = JavaToKotlinUI.NAME

    /**
     * Resolves the Java source file the action should convert.
     *
     * The node selection wins, because it is what the Projects/Files popup is built for. The
     * editor's Refactor submenu renders from the same folder, and NetBeans editors do not always
     * contribute their file's node to the activated nodes, so the global context is used as a
     * fallback there.
     *
     * @param nodes nodes activated when the action is invoked
     * @return the Java file to convert, or `null` when the selection is not exactly one Java file
     */
    internal fun targetFile(nodes: Array<Node>): FileObject? =
        javaFileAmong(nodes) ?: javaFileIn(Utilities.actionsGlobalContext().lookup(DataObject::class.java))

    /**
     * Returns the Java source file selected through [nodes].
     *
     * @param nodes nodes activated when the action is invoked
     * @return the selected Java file, or `null` when zero or several nodes are selected or the
     * selected file is not Java
     */
    internal fun javaFileAmong(nodes: Array<Node>): FileObject? =
        if (nodes.size == 1) javaFileIn(nodes[0].lookup.lookup(DataObject::class.java)) else null

    /**
     * Returns [dataObject]'s primary file when it is a Java source file.
     *
     * @param dataObject the data object to inspect, possibly `null`
     * @return the Java source file, or `null` for `null` input and for non-Java files
     */
    private fun javaFileIn(dataObject: DataObject?): FileObject? =
        dataObject?.primaryFile?.takeIf { it.mimeType == JAVA_MIME_TYPE }

    companion object {
        /** Action name used by the manual layer.xml registration. */
        const val ACTION_NAME = "convert-to-kotlin"

        /** MIME type of Java source files, the only mime type this action accepts. */
        const val JAVA_MIME_TYPE = "text/x-java"

        /** Icon shown in the Refactor menu and on the file node. */
        private const val KOTLIN_ICON_PATH = "org/jetbrains/kotlin/kotlin.png"
    }
}
