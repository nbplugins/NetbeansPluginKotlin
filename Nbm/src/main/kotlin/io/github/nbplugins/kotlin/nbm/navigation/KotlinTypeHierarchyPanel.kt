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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * Switchable view mode for the type hierarchy panel.
 *
 * - [SUPERTYPES]: shows the classes/interfaces that the focal class extends or implements.
 * - [SUBTYPES]: shows the classes that extend or implement the focal class.
 */
enum class HierarchyMode { SUPERTYPES, SUBTYPES }

/**
 * A [JPanel] that displays a Kotlin type hierarchy as a [JTree].
 *
 * The toolbar provides toggle buttons to switch between [HierarchyMode.SUPERTYPES] and
 * [HierarchyMode.SUBTYPES]. Recomputation runs on a background [RequestProcessor] thread
 * so the EDT is not blocked during project-wide scans.
 *
 * Double-clicking a tree node with a known [KotlinTypeHierarchyNode.fileObject] opens the
 * corresponding source file in the editor at the class declaration offset.
 *
 * This class belongs to the **view** layer.
 */
class KotlinTypeHierarchyPanel : JPanel(BorderLayout()) {

    private val tree = JTree(DefaultTreeModel(DefaultMutableTreeNode("(no class selected)"))).apply {
        isRootVisible = true
        cellRenderer = HierarchyTreeCellRenderer()
    }
    private val statusLabel = JLabel(" ")
    private var currentRoot: KotlinTypeHierarchyNode? = null
    private var currentProject: Project? = null
    private var currentMode = HierarchyMode.SUBTYPES

    init {
        val toolbar = buildToolbar()
        add(toolbar, BorderLayout.NORTH)
        add(JScrollPane(tree), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelectedNode()
            }
        })
    }

    /**
     * Loads the hierarchy for [rootNode], shows [mode] by default, and triggers background
     * recomputation using [project]'s source files.
     *
     * Safe to call from any thread; delegates UI updates to the EDT.
     *
     * @param rootNode The focal class node to display at the tree root.
     * @param project The NetBeans project used to scan for subtypes.
     * @param mode The initial view mode (defaults to [HierarchyMode.SUBTYPES]).
     */
    fun setRoot(rootNode: KotlinTypeHierarchyNode, project: Project, mode: HierarchyMode = HierarchyMode.SUBTYPES) {
        currentRoot = rootNode
        currentProject = project
        currentMode = mode
        recompute()
    }

    private fun buildToolbar(): JToolBar {
        val subtypesBtn = JToggleButton("Subtypes", true)
        val supertypesBtn = JToggleButton("Supertypes")
        val group = ButtonGroup()
        group.add(subtypesBtn)
        group.add(supertypesBtn)

        subtypesBtn.addActionListener {
            currentMode = HierarchyMode.SUBTYPES
            recompute()
        }
        supertypesBtn.addActionListener {
            currentMode = HierarchyMode.SUPERTYPES
            recompute()
        }

        return JToolBar().apply {
            isFloatable = false
            add(subtypesBtn)
            add(supertypesBtn)
        }
    }

    private fun recompute() {
        val root = currentRoot ?: return
        val project = currentProject ?: return
        SwingUtilities.invokeLater { statusLabel.text = " Computing…" }

        RequestProcessor.getDefault().post {
            try {
                val session = KotlinAnalysisAPISession.getSession(project)
                val computer = KotlinTypeHierarchyComputer()
                val psi = root.psi

                val children: List<KotlinTypeHierarchyNode> = if (psi != null) {
                    when (currentMode) {
                        HierarchyMode.SUPERTYPES -> computer.computeSupertypes(psi, session)
                        HierarchyMode.SUBTYPES -> computer.computeSubtypes(psi, project, session)
                    }
                } else emptyList()

                SwingUtilities.invokeLater {
                    val rootTreeNode = DefaultMutableTreeNode(root)
                    for (child in children) {
                        rootTreeNode.add(DefaultMutableTreeNode(child))
                    }
                    (tree.model as DefaultTreeModel).setRoot(rootTreeNode)
                    // Expand the root to show its children immediately.
                    tree.expandRow(0)
                    statusLabel.text = " ${children.size} ${if (currentMode == HierarchyMode.SUBTYPES) "subtype(s)" else "supertype(s)"}"
                }
            } catch (e: Exception) {
                KotlinLogger.INSTANCE.logException("KotlinTypeHierarchyPanel recompute failed", e)
                SwingUtilities.invokeLater { statusLabel.text = " Error computing hierarchy" }
            }
        }
    }

    private fun navigateToSelectedNode() {
        val path = tree.selectionPath ?: return
        val treeNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val node = treeNode.userObject as? KotlinTypeHierarchyNode ?: return
        val psi = node.psi as? KtClassOrObject ?: return
        val fo = node.fileObject ?: return

        runCatching {
            val doc = ProjectUtils.getDocumentFromFileObject(fo) as? javax.swing.text.StyledDocument ?: return
            val offset = LineEndUtil.convertCrToDocumentOffset(psi.containingFile.text, psi.textOffset)
            openFileAtOffset(doc, offset)
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinTypeHierarchyPanel navigate failed", e)
        }
    }

    /**
     * Custom tree cell renderer that shows the class name and dims nodes that have no
     * navigable source (i.e. [KotlinTypeHierarchyNode.fileObject] is null).
     */
    private class HierarchyTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val treeNode = value as? DefaultMutableTreeNode
            val node = treeNode?.userObject as? KotlinTypeHierarchyNode
            if (node != null) {
                text = buildString {
                    append(node.name)
                    if (node.fqName != null && node.fqName != node.name) {
                        val pkg = node.fqName.substringBeforeLast('.', "")
                        if (pkg.isNotEmpty()) append("  ($pkg)")
                    }
                }
                if (node.fileObject == null) {
                    foreground = Color.GRAY
                    border = BorderFactory.createEmptyBorder()
                }
            }
            return component
        }
    }
}
