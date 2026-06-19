/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.navigation

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.utils.LineEndUtil
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.project.Project
import org.openide.util.RequestProcessor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Collections
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Switchable view mode for the type hierarchy panel.
 *
 * - [SUPERTYPES]: shows classes/interfaces that the focal class extends or implements (upward).
 * - [SUBTYPES]: shows classes that extend or implement the focal class (downward).
 */
enum class HierarchyMode { SUPERTYPES, SUBTYPES }

/**
 * An interactive [JPanel] that displays a Kotlin type hierarchy as a lazily-expanded [JTree].
 *
 * **UI layout:**
 * ```
 * [← Back]  |  [Subtypes]  [Supertypes]  |  [→ Focus]
 * ┌────────────────────────────────────────┐
 * │ ▼ BaseClass  (navigation)              │  ← focal root
 * │   ▶ DerivedClass  (navigation)         │  ← expandable (loads lazily)
 * │   ▼ ConcreteImpl  (navigation)         │  ← expanded
 * │       ▶ MoreDerived  (navigation)      │
 * └────────────────────────────────────────┘
 *  3 direct subtype(s)
 * ```
 *
 * **Interactions:**
 * - Expanding a node (▶) loads its children in the background via [KotlinTypeHierarchyComputer].
 * - Single left-click on a node navigates the editor to that class declaration.
 * - **Enter** or **→ Focus** makes the selected node the new focal root and pushes the old one
 *   onto the Back stack.
 * - **← Back** pops the stack and restores the previous focal root.
 * - **Subtypes / Supertypes** toggles the view mode and reloads from the current root.
 *
 * This class belongs to the **view** layer.
 */
class KotlinTypeHierarchyPanel : JPanel(BorderLayout()) {

    /**
     * Singleton sentinel placed as the sole child of a node to make the expand arrow appear
     * before the real children have been loaded. Replaced on first expansion.
     * Its [toString] returns the visible placeholder text.
     */
    private object LoadingPlaceholder {
        override fun toString() = "Loading…"
    }

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("(no class selected)"))
    private val tree = JTree(treeModel).apply {
        isRootVisible = true
        cellRenderer = HierarchyTreeCellRenderer()
    }
    private val statusLabel = JLabel(" ")

    private var currentRoot: KotlinTypeHierarchyNode? = null
    private var currentProject: Project? = null
    private var currentMode = HierarchyMode.SUBTYPES

    /** Browser-style navigation stack for the Back button. */
    private val history = ArrayDeque<KotlinTypeHierarchyNode>()

    /** Guards against starting a second background load for the same tree node. */
    private val loadingInProgress: MutableSet<DefaultMutableTreeNode> =
        Collections.synchronizedSet(mutableSetOf())

    private lateinit var backBtn: JButton
    private lateinit var focusBtn: JButton

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(JScrollPane(tree), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        wireListeners()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sets the focal class and reloads the hierarchy tree.
     *
     * Safe to call from any thread; all UI updates are dispatched to the EDT.
     *
     * @param rootNode The class or interface to display as the tree root.
     * @param project The project whose source files are scanned for relatives.
     * @param mode The view mode (subtypes or supertypes).
     * @param addToHistory If `true`, pushes the current root onto the Back history stack before
     *   switching, enabling the **← Back** button.
     */
    fun setRoot(
        rootNode: KotlinTypeHierarchyNode,
        project: Project,
        mode: HierarchyMode = HierarchyMode.SUBTYPES,
        addToHistory: Boolean = false
    ) {
        if (addToHistory) currentRoot?.let { history.addLast(it) }
        currentRoot = rootNode
        currentProject = project
        currentMode = mode
        loadingInProgress.clear()
        SwingUtilities.invokeLater {
            backBtn.isEnabled = history.isNotEmpty()
            focusBtn.isEnabled = false
        }
        recompute()
    }

    // -------------------------------------------------------------------------
    // Toolbar
    // -------------------------------------------------------------------------

    private fun buildToolbar(): JToolBar {
        backBtn = JButton("← Back").apply {
            isEnabled = false
            toolTipText = "Go back to the previous class in history"
            addActionListener { navigateBack() }
        }

        val subtypesBtn = JToggleButton("Subtypes", true)
        val supertypesBtn = JToggleButton("Supertypes")
        ButtonGroup().apply { add(subtypesBtn); add(supertypesBtn) }
        subtypesBtn.addActionListener { switchModeTo(HierarchyMode.SUBTYPES) }
        supertypesBtn.addActionListener { switchModeTo(HierarchyMode.SUPERTYPES) }

        focusBtn = JButton("→ Focus").apply {
            isEnabled = false
            toolTipText = "Make selected class the hierarchy root (Enter)"
            addActionListener { focusOnSelected() }
        }

        return JToolBar().apply {
            isFloatable = false
            add(backBtn)
            addSeparator()
            add(subtypesBtn)
            add(supertypesBtn)
            addSeparator()
            add(focusBtn)
        }
    }

    // -------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------

    private fun wireListeners() {
        // Single left-click → navigate to source in editor.
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = (path.lastPathComponent as? DefaultMutableTreeNode)
                        ?.userObject as? KotlinTypeHierarchyNode ?: return
                    navigateToNode(node)
                }
            }
        })

        // Enter key → re-focus hierarchy on the selected node.
        tree.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "focusOnSelected")
        tree.actionMap.put("focusOnSelected", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { focusOnSelected() }
        })

        // Enable/disable Focus button based on what is selected.
        tree.addTreeSelectionListener {
            val node = selectedHierarchyNode()
            focusBtn.isEnabled = node != null && node != currentRoot
        }

        // Lazy expansion: trigger background load when a node with a placeholder is expanded.
        tree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val parent = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                if (parent in loadingInProgress) return
                if (parent.childCount == 1) {
                    val child = parent.getChildAt(0) as? DefaultMutableTreeNode ?: return
                    if (child.userObject === LoadingPlaceholder) {
                        loadChildrenAsync(parent)
                    }
                }
            }
            override fun treeWillCollapse(e: TreeExpansionEvent) {}
        })
    }

    // -------------------------------------------------------------------------
    // Root-level recompute
    // -------------------------------------------------------------------------

    /**
     * Recomputes the hierarchy tree for [currentRoot] in [currentMode].
     *
     * @param onTreeBuilt Optional EDT callback invoked after the tree model is replaced.
     *   Receives the newly created root [DefaultMutableTreeNode] so callers can search
     *   for a node to select or take other post-load actions.
     */
    private fun recompute(onTreeBuilt: ((DefaultMutableTreeNode) -> Unit)? = null) {
        val root = currentRoot ?: return
        val project = currentProject ?: return
        SwingUtilities.invokeLater { statusLabel.text = " Computing…" }

        RequestProcessor.getDefault().post {
            try {
                val session = KotlinAnalysisAPISession.getSession(project)
                val psi = root.psi
                val children: List<KotlinTypeHierarchyNode> = if (psi != null) {
                    val computer = KotlinTypeHierarchyComputer()
                    when (currentMode) {
                        HierarchyMode.SUBTYPES -> computer.computeSubtypes(psi, project, session)
                        HierarchyMode.SUPERTYPES -> computer.computeSupertypes(psi, session)
                    }
                } else emptyList()

                SwingUtilities.invokeLater {
                    val rootTreeNode = DefaultMutableTreeNode(root)
                    for (child in children) rootTreeNode.add(makeTreeNode(child))
                    treeModel.setRoot(rootTreeNode)
                    tree.expandRow(0)
                    val label = if (currentMode == HierarchyMode.SUBTYPES) "subtype(s)" else "supertype(s)"
                    statusLabel.text = " ${children.size} direct $label"
                    onTreeBuilt?.invoke(rootTreeNode)
                }
            } catch (e: Exception) {
                KotlinLogger.INSTANCE.logException("KotlinTypeHierarchyPanel.recompute failed", e)
                SwingUtilities.invokeLater { statusLabel.text = " Error computing hierarchy" }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lazy child loading
    // -------------------------------------------------------------------------

    /**
     * Computes children for [parent] in the background and replaces the loading placeholder
     * with the real children once the computation finishes.
     *
     * [parent] must contain exactly one child: the [LoadingPlaceholder] sentinel.
     * On EDT, the placeholder is replaced; if there are no children the node becomes a true leaf.
     */
    private fun loadChildrenAsync(parent: DefaultMutableTreeNode) {
        val node = parent.userObject as? KotlinTypeHierarchyNode ?: return
        val project = currentProject ?: return
        loadingInProgress.add(parent)

        RequestProcessor.getDefault().post {
            val children: List<KotlinTypeHierarchyNode> = try {
                val psi = node.psi
                if (psi != null) {
                    val session = KotlinAnalysisAPISession.getSession(project)
                    val computer = KotlinTypeHierarchyComputer()
                    when (currentMode) {
                        HierarchyMode.SUBTYPES -> computer.computeSubtypes(psi, project, session)
                        HierarchyMode.SUPERTYPES -> computer.computeSupertypes(psi, session)
                    }
                } else emptyList()
            } catch (e: Exception) {
                KotlinLogger.INSTANCE.logException("KotlinTypeHierarchyPanel.loadChildrenAsync failed", e)
                emptyList()
            }

            SwingUtilities.invokeLater {
                loadingInProgress.remove(parent)
                parent.removeAllChildren()
                for (child in children) parent.add(makeTreeNode(child))
                treeModel.nodeStructureChanged(parent)
            }
        }
    }

    /**
     * Creates a [DefaultMutableTreeNode] for [node] with a [LoadingPlaceholder] child so the
     * expand arrow appears before children are loaded.
     */
    private fun makeTreeNode(node: KotlinTypeHierarchyNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).also { it.add(DefaultMutableTreeNode(LoadingPlaceholder)) }

    // -------------------------------------------------------------------------
    // Navigation actions
    // -------------------------------------------------------------------------

    /** Makes the currently selected node the new focal root, pushing the old root to history. */
    private fun focusOnSelected() {
        val node = selectedHierarchyNode() ?: return
        val project = currentProject ?: return
        setRoot(node, project, currentMode, addToHistory = true)
    }

    /**
     * Switches the view mode to [mode] and reloads the tree.
     *
     * If a non-root node is currently selected, the tree is loaded for [currentRoot] first.
     * After loading, we check whether the selected node appears among the direct children:
     * - If **found**: it is selected and scrolled into view (the user stays in the same root context).
     * - If **not found**: the selected node becomes the new focal root (pushed to history).
     *
     * If nothing is selected (or the root itself is selected), the mode switches and the tree
     * is reloaded for [currentRoot] without any position adjustment.
     */
    private fun switchModeTo(mode: HierarchyMode) {
        val selected = selectedHierarchyNode()
        currentMode = mode
        if (selected != null && selected != currentRoot) {
            recompute { rootTreeNode ->
                val match = findDirectChild(rootTreeNode, selected.fqName)
                if (match != null) {
                    val path = TreePath(treeModel.getPathToRoot(match))
                    tree.selectionPath = path
                    tree.scrollPathToVisible(path)
                } else {
                    setRoot(selected, currentProject ?: return@recompute, currentMode, addToHistory = true)
                }
            }
        } else {
            recompute()
        }
    }

    /**
     * Searches the direct children of [parent] for a [KotlinTypeHierarchyNode] whose
     * [KotlinTypeHierarchyNode.fqName] equals [fqName].
     *
     * Only one level deep — does not recurse into grandchildren.
     */
    private fun findDirectChild(parent: DefaultMutableTreeNode, fqName: String?): DefaultMutableTreeNode? {
        if (fqName == null) return null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            val node = child.userObject as? KotlinTypeHierarchyNode ?: continue
            if (node.fqName == fqName) return child
        }
        return null
    }

    /** Pops the navigation history and restores the previous focal root. */
    private fun navigateBack() {
        val prev = history.removeLastOrNull() ?: return
        val project = currentProject ?: return
        setRoot(prev, project, currentMode, addToHistory = false)
    }

    /** Returns the [KotlinTypeHierarchyNode] for the currently selected tree row, or null. */
    private fun selectedHierarchyNode(): KotlinTypeHierarchyNode? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)
            ?.userObject as? KotlinTypeHierarchyNode

    /**
     * Opens the source file for [node] in the editor, positioned at the class declaration.
     *
     * Does nothing if [node] has no [KotlinTypeHierarchyNode.fileObject].
     */
    private fun navigateToNode(node: KotlinTypeHierarchyNode) {
        val psi = node.psi as? KtClassOrObject ?: return
        val fo = node.fileObject ?: return
        runCatching {
            val doc = ProjectUtils.getDocumentFromFileObject(fo)
                as? javax.swing.text.StyledDocument ?: return
            val offset = LineEndUtil.convertCrToDocumentOffset(psi.containingFile.text, psi.textOffset)
            openFileAtOffset(doc, offset)
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinTypeHierarchyPanel.navigateToNode failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Cell renderer
    // -------------------------------------------------------------------------

    /**
     * Renders [KotlinTypeHierarchyNode] cells with their simple name and (package) suffix.
     * Nodes with no navigable source ([KotlinTypeHierarchyNode.fileObject] is null) and
     * loading-placeholder nodes are rendered in grey.
     */
    private class HierarchyTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val obj = (value as? DefaultMutableTreeNode)?.userObject
            if (obj is KotlinTypeHierarchyNode) {
                text = buildString {
                    append(obj.name)
                    if (obj.fqName != null && obj.fqName != obj.name) {
                        val pkg = obj.fqName.substringBeforeLast('.', "")
                        if (pkg.isNotEmpty()) append("  ($pkg)")
                    }
                }
                if (obj.fileObject == null) foreground = Color.GRAY
            } else {
                // Loading placeholder — text is already set by super via LoadingPlaceholder.toString()
                foreground = Color.GRAY
            }
            return this
        }
    }
}
