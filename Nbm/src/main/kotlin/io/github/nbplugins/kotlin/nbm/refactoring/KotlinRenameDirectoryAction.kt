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
import javax.swing.Action
import javax.swing.JSeparator
import org.jetbrains.kotlin.log.KotlinLogger
import org.netbeans.modules.refactoring.spi.ui.ActionsImplementationProvider
import org.netbeans.modules.refactoring.spi.ui.UI
import org.netbeans.api.project.SourceGroup
import org.openide.awt.ActionID
import org.openide.awt.ActionRegistration
import org.openide.util.actions.Presenter
import org.openide.cookies.InstanceCookie
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataFolder
import org.openide.loaders.DataObject
import org.openide.nodes.Node
import org.openide.util.ContextAwareAction
import org.openide.util.HelpCtx
import org.openide.util.Lookup
import org.openide.util.RequestProcessor
import org.openide.util.Utilities
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
        KotlinLogger.INSTANCE.logInfo("KotlinRenameDirectoryAction: invoked with ${nodes.size} selected node(s)")
        logSelectedNodeActions(nodes)
        logRenameEligibility(nodes)
        logRefactoringMenuRegistration(nodes)
        val selection = selectedSelection(nodes)
        if (selection == null) {
            KotlinLogger.INSTANCE.logInfo("KotlinRenameDirectoryAction: aborted because no Kotlin package directory was selected")
            return
        }
        val folder = selection.selectedDirectory
        if (folder == null) {
            KotlinLogger.INSTANCE.logInfo("KotlinRenameDirectoryAction: aborted because the selected source is not a directory")
            return
        }
        KotlinLogger.INSTANCE.logInfo(
            "KotlinRenameDirectoryAction: context resolved for ${folder.path} with ${selection.sourceFiles.size} Kotlin file(s)",
        )
        KotlinLogger.INSTANCE.logInfo("KotlinRenameDirectoryAction: scheduling UI preparation")
        REQUEST_PROCESSOR.post {
            KotlinLogger.INSTANCE.logInfo("KotlinRenameDirectoryAction: scheduling refactoring UI on EDT")
            EventQueue.invokeLater {
                runCatching {
                    KotlinLogger.INSTANCE.logInfo("KotlinRenameDirectoryAction: opening refactoring UI on EDT")
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

    /**
     * Records the selected Project Explorer node's lookup and effective popup actions.
     *
     * The record is intentionally emitted only while a known working directory action runs so it
     * can compare Kotlin source/package nodes with Java source/package nodes without startup noise.
     *
     * @param nodes nodes selected when the directory action was invoked
     */
    private fun logSelectedNodeActions(nodes: Array<Node>) {
        nodes.forEachIndexed { index, node ->
            val lookup = node.lookup
            val parent = node.parentNode
            val dataObject = lookup.lookup(DataObject::class.java)
            val dataFolder = lookup.lookup(DataFolder::class.java)
            val fileObject = lookup.lookup(FileObject::class.java)
            val sourceGroup = lookup.lookup(SourceGroup::class.java)
            KotlinLogger.INSTANCE.logInfo(
                "KotlinRenameDirectoryAction: selected node diagnostic index=$index " +
                    "class=${node.javaClass.name} name=${node.name} displayName=${node.displayName} " +
                    "parentClass=${parent?.javaClass?.name} parentName=${parent?.name} " +
                    "parentDisplayName=${parent?.displayName} " +
                    "dataObject=${dataObject?.javaClass?.name} " +
                    "dataFolder=${dataFolder?.primaryFile?.path} " +
                    "fileObject=${fileObject?.path} " +
                    "sourceGroup=${sourceGroup?.javaClass?.name} " +
                    "sourceRoot=${sourceGroup?.rootFolder?.path}",
            )
            node.getActions(false).forEachIndexed { actionIndex, action ->
                if (action == null) {
                    KotlinLogger.INSTANCE.logInfo(
                        "KotlinRenameDirectoryAction: selected node action index=$index " +
                            "actionIndex=$actionIndex kind=separator",
                    )
                    return@forEachIndexed
                }
                KotlinLogger.INSTANCE.logInfo(
                    "KotlinRenameDirectoryAction: selected node action index=$index actionIndex=$actionIndex " +
                        "class=${action.javaClass.name} name=${action.getValue(Action.NAME)} " +
                        "enabled=${action.isEnabled} " +
                        "popupPresenter=${action is Presenter.Popup} " +
                        "contextAware=${action is ContextAwareAction} " +
                        "identity=${System.identityHashCode(action)}",
                )
                if (action.javaClass.name == REFACTOR_SUBMENU_ACTION_CLASS) {
                    logContextAwarePopupAction(index, actionIndex, action, lookup)
                }
            }
        }
    }

    /**
     * Records aggregate and per-provider rename eligibility for the two relevant action contexts.
     *
     * The Projects popup obtains a global context containing its selected node, while the earlier
     * node diagnostic resolves actions from the node lookup directly. Both are recorded because a
     * missing node in either context would make the provider result incomparable to the popup.
     *
     * @param nodes nodes selected when the directory action was invoked
     */
    private fun logRenameEligibility(nodes: Array<Node>) {
        logRenameEligibility("active-projects", Utilities.actionsGlobalContext())
        nodes.forEachIndexed { index, node ->
            logRenameEligibility("selected-node-$index", node.lookup)
        }
    }

    /**
     * Records aggregate and individual provider results for one refactoring action context.
     *
     * Each provider is isolated because diagnostic inspection must not interfere with the already
     * validated directory action if an unrelated provider fails while examining its context.
     *
     * @param contextLabel stable label identifying the action context being inspected
     * @param context lookup supplied to NetBeans refactoring actions
     */
    private fun logRenameEligibility(contextLabel: String, context: Lookup) {
        val contextNodes = context.lookupAll(Node::class.java)
        val nodeDescription = contextNodes.joinToString { node ->
            "${node.javaClass.name}(name=${node.name},displayName=${node.displayName})"
        }
        KotlinLogger.INSTANCE.logInfo(
            "KotlinRenameDirectoryAction: rename eligibility context=$contextLabel " +
                "nodeCount=${contextNodes.size} nodes=[$nodeDescription]",
        )
        runCatching { Lookup.getDefault().lookupAll(ActionsImplementationProvider::class.java) }
            .onSuccess { providers ->
                KotlinLogger.INSTANCE.logInfo(
                    "KotlinRenameDirectoryAction: rename eligibility providers context=$contextLabel " +
                        "count=${providers.size}",
                )
                providers.forEachIndexed { providerIndex, provider ->
                    runCatching { provider.canRename(context) }
                        .onSuccess { canRename ->
                            KotlinLogger.INSTANCE.logInfo(
                                "KotlinRenameDirectoryAction: rename eligibility provider " +
                                    "context=$contextLabel providerIndex=$providerIndex " +
                                    "class=${provider.javaClass.name} canRename=$canRename",
                            )
                        }
                        .onFailure { error ->
                            KotlinLogger.INSTANCE.logException(
                                "KotlinRenameDirectoryAction: rename eligibility provider failed " +
                                    "context=$contextLabel providerIndex=$providerIndex " +
                                    "class=${provider.javaClass.name}",
                                error,
                            )
                        }
                }
            }
            .onFailure { error ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinRenameDirectoryAction: rename eligibility provider lookup failed " +
                        "context=$contextLabel",
                    error,
                )
            }
    }

    /**
     * Records the action that NetBeans uses after it applies the selected node lookup.
     *
     * Projects invokes the same context-aware conversion before it asks an action for a popup
     * presenter. In particular, the standard Refactor action can be replaced by Rename for one
     * package node but remain a submenu for another.
     *
     * @param nodeIndex index of the selected node being diagnosed
     * @param actionIndex index of the original action in the node action array
     * @param action original action supplied by the node
     * @param context selected node lookup used to resolve the action
     */
    private fun logContextAwarePopupAction(
        nodeIndex: Int,
        actionIndex: Int,
        action: Action,
        context: org.openide.util.Lookup,
    ) {
        if (action !is ContextAwareAction) {
            return
        }
        runCatching { action.createContextAwareInstance(context) }
            .onSuccess { resolvedAction ->
                KotlinLogger.INSTANCE.logInfo(
                    "KotlinRenameDirectoryAction: selected node contextual action index=$nodeIndex " +
                        "actionIndex=$actionIndex originalClass=${action.javaClass.name} " +
                        "originalName=${action.getValue(Action.NAME)} " +
                        "resolvedClass=${resolvedAction.javaClass.name} " +
                        "resolvedName=${resolvedAction.getValue(Action.NAME)} " +
                        "resolvedEnabled=${resolvedAction.isEnabled} " +
                        "resolvedPopupPresenter=${resolvedAction is Presenter.Popup} " +
                        "sameInstance=${resolvedAction === action} " +
                        "resolvedIdentity=${System.identityHashCode(resolvedAction)}",
                )
            }
            .onFailure { error ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinRenameDirectoryAction: selected node contextual action failed " +
                        "index=$nodeIndex actionIndex=$actionIndex class=${action.javaClass.name}",
                    error,
                )
            }
    }

    /**
     * Logs the effective popup registry at the moment a known working Projects action runs.
     *
     * NetBeans' RefactoringSubMenuAction uses this same DataFolder/InstanceCookie walk to create
     * the Refactor popup, so this reveals the actual action behind an ambiguous display label.
     *
     * @param nodes nodes selected when the Refactor popup action was invoked
     */
    private fun logRefactoringMenuRegistration(nodes: Array<Node>) {
        val selectedNodes = nodes.joinToString { node -> "${node.javaClass.name}(name=${node.name})" }
        KotlinLogger.INSTANCE.logInfo(
            "KotlinRenameDirectoryAction: Refactor menu diagnostic selectedNodes=[$selectedNodes]",
        )
        val menuFolder = FileUtil.getConfigFile(REFACTOR_MENU_PATH)
        if (menuFolder == null) {
            KotlinLogger.INSTANCE.logWarning(
                "KotlinRenameDirectoryAction: Refactor menu diagnostic missing $REFACTOR_MENU_PATH",
            )
            return
        }
        val folder = runCatching { DataFolder.findFolder(menuFolder) }.getOrElse { error ->
            KotlinLogger.INSTANCE.logException(
                "KotlinRenameDirectoryAction: Refactor menu diagnostic could not open ${menuFolder.path}",
                error,
            )
            return
        }
        KotlinLogger.INSTANCE.logInfo(
            "KotlinRenameDirectoryAction: Refactor menu diagnostic inspecting ${menuFolder.path} " +
                "with ${folder.children.size} registration(s)",
        )
        folder.children.forEach { registration ->
            val configFile = registration.primaryFile
            val origin = configFile.getAttribute("originalFile")
            val position = configFile.getAttribute("position")
            val prefix = "KotlinRenameDirectoryAction: Refactor menu entry path=${configFile.path} " +
                "originalFile=$origin position=$position"
            val instanceCookie = registration.lookup.lookup(InstanceCookie::class.java)
            if (instanceCookie == null) {
                KotlinLogger.INSTANCE.logInfo("$prefix kind=no-instance-cookie")
                return@forEach
            }
            runCatching { instanceCookie.instanceCreate() }
                .onSuccess { instance ->
                    when (instance) {
                        is Action -> KotlinLogger.INSTANCE.logInfo(
                            "$prefix kind=action class=${instance.javaClass.name} " +
                                "name=${instance.getValue(Action.NAME)} " +
                                "shortDescription=${instance.getValue(Action.SHORT_DESCRIPTION)} " +
                                "enabled=${instance.isEnabled} identity=${System.identityHashCode(instance)}",
                        )
                        is JSeparator -> KotlinLogger.INSTANCE.logInfo("$prefix kind=separator")
                        else -> KotlinLogger.INSTANCE.logInfo(
                            "$prefix kind=non-action class=${instance?.javaClass?.name ?: "null"}",
                        )
                    }
                }
                .onFailure { error ->
                    KotlinLogger.INSTANCE.logException("$prefix kind=instance-creation-failed", error)
                }
        }
    }

    /** Resolves the non-empty Kotlin selection validated before the refactoring UI is opened. */
    private fun selectedSelection(nodes: Array<Node>): KotlinMoveSourceSelection? = KotlinFolderNodeSelection.resolve(nodes)
        ?.let(KotlinMoveSourceSelection::from)
        ?.takeIf(KotlinMoveSourceSelection::isDirectory)

    private companion object {
        /** Effective configuration folder read lazily by NetBeans' Refactor popup implementation. */
        const val REFACTOR_MENU_PATH = "Menu/Refactoring"

        /** NetBeans action whose context-aware conversion decides whether Refactor remains a submenu. */
        const val REFACTOR_SUBMENU_ACTION_CLASS =
            "org.netbeans.modules.refactoring.spi.impl.RSMDataObjectAction"

        /** Worker used for filesystem discovery before the Swing refactoring UI is shown. */
        val REQUEST_PROCESSOR = RequestProcessor(KotlinRenameDirectoryAction::class.java.name, 1)
    }
}
