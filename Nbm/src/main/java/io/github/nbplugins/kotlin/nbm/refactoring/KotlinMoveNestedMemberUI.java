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
package io.github.nbplugins.kotlin.nbm.refactoring;

import io.github.nbplugins.kotlin.refactoring.KaMoveNestedMemberComputer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.ChangeListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.explorer.ExplorerManager;
import org.openide.explorer.view.BeanTreeView;
import org.openide.nodes.AbstractNode;
import org.openide.nodes.Children;
import org.openide.nodes.Node;
import org.openide.util.HelpCtx;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

/**
 * Passive NetBeans view for choosing a Move Nested Member destination.
 *
 * It receives K2 discovery data from its controller and writes only the selected file/offset to the
 * carrier; semantic conflict checks remain in {@link KotlinMoveNestedMemberPlugin}. The chooser uses
 * NetBeans' explorer tree to retain the package and class-container structure of the supplied data.
 */
public final class KotlinMoveNestedMemberUI implements RefactoringUI {
    private final KaMoveNestedMemberComputer.Outcome.Ready ready;
    private final List<KaMoveNestedMemberComputer.TargetCandidate> candidates;
    private final KotlinMoveNestedMemberRefactoring refactoring;
    private TargetPanel panel;

    /**
     * Creates a target-selection view.
     *
     * @param ready source declaration presentation and source-tree location from the K2 controller
     * @param candidates compatible targets computed by the K2 controller
     * @param refactoring carrier populated on confirmation
     */
    public KotlinMoveNestedMemberUI(KaMoveNestedMemberComputer.Outcome.Ready ready,
                                    List<KaMoveNestedMemberComputer.TargetCandidate> candidates,
                                    KotlinMoveNestedMemberRefactoring refactoring) {
        this.ready = ready;
        this.candidates = candidates;
        this.refactoring = refactoring;
    }

    /** @return operation label shown by NetBeans. */
    @Override public String getName() { return "Move Nested Member"; }

    /** @return description naming the declaration selected at the editor caret. */
    @Override public String getDescription() { return "Move nested member '" + ready.getDeclarationName() + "'"; }

    /** @return {@code false}; this operation changes Kotlin source. */
    @Override public boolean isQuery() { return false; }

    /** @return the reusable passive target chooser. */
    @Override public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) panel = new TargetPanel(ready, candidates, parent);
        return panel;
    }

    /** Copies the selected target candidate to the controller carrier. */
    @Override public Problem setParameters() {
        if (panel != null) panel.copyTo(refactoring);
        return checkParameters();
    }

    /** @return fatal validation problem when no concrete target was selected. */
    @Override public Problem checkParameters() {
        if (panel != null && panel.selected() == null) {
            return new Problem(true, "Select a target class or object.");
        }
        return null;
    }

    /** @return {@code true}; the user chooses a destination. */
    @Override public boolean hasParameters() { return true; }

    /** @return refactoring carrier accepted by the plugin factory. */
    @Override public AbstractRefactoring getRefactoring() { return refactoring; }

    /** @return no dedicated help page currently exists. */
    @Override public HelpCtx getHelpCtx() { return null; }

    /** Swing-only target chooser; deliberately contains no K2/project/session/filesystem calls. */
    private static final class TargetPanel extends JPanel
            implements CustomRefactoringPanel, ExplorerManager.Provider {
        private final ExplorerManager explorerManager = new ExplorerManager();
        private final BeanTreeView targets = new BeanTreeView();
        private final Map<String, TargetNode> nodesByPath = new LinkedHashMap<>();
        private final KaMoveNestedMemberComputer.Outcome.Ready ready;

        /** Creates a structured chooser from immutable controller-supplied candidate data. */
        TargetPanel(KaMoveNestedMemberComputer.Outcome.Ready ready,
                    List<KaMoveNestedMemberComputer.TargetCandidate> candidates,
                    ChangeListener changeListener) {
            this.ready = ready;
            setLayout(new BorderLayout());

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
            GridBagConstraints label = new GridBagConstraints();
            label.anchor = GridBagConstraints.WEST;
            label.insets = new Insets(2, 0, 2, 8);
            GridBagConstraints field = new GridBagConstraints();
            field.fill = GridBagConstraints.HORIZONTAL;
            field.weightx = 1.0;
            field.gridwidth = GridBagConstraints.REMAINDER;
            field.insets = new Insets(2, 0, 2, 0);

            form.add(new JLabel("Member:"), label);
            form.add(new JLabel(ready.getDeclarationName()), field);
            JLabel targetLabel = new JLabel(ready.getRequiresCompanionTarget()
                    ? "Target class/object with companion:" : "Target class/object:");
            targetLabel.setLabelFor(targets);
            form.add(targetLabel, label);
            form.add(new JLabel("Choose a class or object in the tree below."), field);
            add(form, BorderLayout.NORTH);

            TargetNode root = targetTree(candidates);
            explorerManager.setRootContext(root);
            targets.setRootVisible(false);
            targets.setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
            targets.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
            add(targets, BorderLayout.CENTER);
            explorerManager.addPropertyChangeListener((PropertyChangeEvent event) -> {
                if (ExplorerManager.PROP_SELECTED_NODES.equals(event.getPropertyName())) {
                    changeListener.stateChanged(null);
                }
            });
        }

        /** @return this panel's explorer context required by {@link BeanTreeView}. */
        @Override public ExplorerManager getExplorerManager() { return explorerManager; }

        /**
         * Builds every explorer node only after all structural paths and selectable candidates are known.
         *
         * Nodes may have exactly one parent; constructing a replacement node after its structural node
         * has acquired children makes {@code Children.Array} recursively assign already-owned nodes.
         */
        private TargetNode targetTree(List<KaMoveNestedMemberComputer.TargetCandidate> candidates) {
            Map<String, TreeEntry> entries = new LinkedHashMap<>();
            entries.put("", new TreeEntry("", "Targets", null, null));
            candidates.forEach(candidate -> addCandidate(entries, candidate));
            addSourcePath(entries);

            List<TreeEntry> ordered = new ArrayList<>(entries.values());
            ordered.sort(Comparator.comparingInt(TreeEntry::depth));
            for (TreeEntry entry : ordered) {
                TargetNode node = new TargetNode(entry.displayName, entry.candidate);
                entry.node = node;
                nodesByPath.put(entry.path, node);
                if (entry.parentPath != null) entries.get(entry.parentPath).node.add(node);
            }
            return entries.get("").node;
        }

        /** Adds one selectable candidate and its package/container path to the immutable tree model. */
        private void addCandidate(Map<String, TreeEntry> entries,
                                  KaMoveNestedMemberComputer.TargetCandidate candidate) {
            String parentPath = "";
            String packagePath = packageKey(candidate.getPackageName());
            final String rootPath = parentPath;
            entries.computeIfAbsent(packagePath, path -> new TreeEntry(path,
                    candidate.getPackageName().isEmpty() ? "(default package)" : candidate.getPackageName(),
                    rootPath, null));
            parentPath = packagePath;
            List<String> containers = candidate.getContainerPath();
            for (int index = 0; index < containers.size(); index++) {
                String container = containers.get(index);
                String path = parentPath + '/' + container;
                boolean leaf = index == containers.size() - 1;
                final String finalParentPath = parentPath;
                entries.compute(path, (ignored, existing) -> existing == null
                        ? new TreeEntry(path, container, finalParentPath, leaf ? candidate : null)
                        : leaf ? existing.withCandidate(candidate) : existing);
                parentPath = path;
            }
        }

        /** Adds the source path as structural entries so its package and top-level owner can expand. */
        private void addSourcePath(Map<String, TreeEntry> entries) {
            String parentPath = "";
            String packagePath = packageKey(ready.getSourcePackageName());
            final String rootPath = parentPath;
            entries.computeIfAbsent(packagePath, path -> new TreeEntry(path,
                    ready.getSourcePackageName().isEmpty() ? "(default package)" : ready.getSourcePackageName(),
                    rootPath, null));
            parentPath = packagePath;
            for (String container : ready.getSourceContainerPath()) {
                String path = parentPath + '/' + container;
                final String finalParentPath = parentPath;
                entries.computeIfAbsent(path, ignored -> new TreeEntry(path, container, finalParentPath, null));
                parentPath = path;
            }
        }

        /** Produces a collision-free internal path prefix for one package. */
        private String packageKey(String packageName) { return "package:" + packageName; }

        /** Copies only the selection; semantic validation is deferred to the plugin. */
        void copyTo(KotlinMoveNestedMemberRefactoring refactoring) {
            KaMoveNestedMemberComputer.TargetCandidate selected = selected();
            refactoring.setTargetFilePath(selected == null ? "" : selected.getFilePath());
            refactoring.setTargetOffset(selected == null ? -1 : selected.getOffset());
        }

        /** @return selected immutable target candidate, if exactly one selectable node is active. */
        KaMoveNestedMemberComputer.TargetCandidate selected() {
            Node[] selectedNodes = explorerManager.getSelectedNodes();
            return selectedNodes.length == 1
                    ? selectedNodes[0].getLookup().lookup(KaMoveNestedMemberComputer.TargetCandidate.class)
                    : null;
        }

        /** Expands the source package and top-level container, then focuses the tree. */
        @Override public void initialize() {
            StringBuilder path = new StringBuilder(packageKey(ready.getSourcePackageName()));
            TargetNode packageNode = nodesByPath.get(path.toString());
            if (packageNode != null) targets.expandNode(packageNode);
            if (!ready.getSourceContainerPath().isEmpty()) {
                path.append('/').append(ready.getSourceContainerPath().get(0));
                TargetNode topLevel = nodesByPath.get(path.toString());
                if (topLevel != null) targets.expandNode(topLevel);
            }
            targets.requestFocusInWindow();
        }

        /** @return this panel itself. */
        @Override public Component getComponent() { return this; }
    }

    /** Immutable path record used to allocate every NetBeans node with its final parent and lookup. */
    private static final class TreeEntry {
        private final String path;
        private final String displayName;
        private final String parentPath;
        private final KaMoveNestedMemberComputer.TargetCandidate candidate;
        private TargetNode node;

        /** Creates one immutable package/container path record. */
        TreeEntry(String path, String displayName, String parentPath,
                  KaMoveNestedMemberComputer.TargetCandidate candidate) {
            this.path = path;
            this.displayName = displayName;
            this.parentPath = parentPath;
            this.candidate = candidate;
        }

        /** @return a copy whose final path component is selectable. */
        TreeEntry withCandidate(KaMoveNestedMemberComputer.TargetCandidate target) {
            return new TreeEntry(path, displayName, parentPath, target);
        }

        /** @return component count used to construct parents before descendants. */
        int depth() { return path.isEmpty() ? 0 : path.split("/").length; }
    }

    /** Explorer node that is selectable only when it contains a concrete target candidate. */
    private static final class TargetNode extends AbstractNode {
        private final Children.Array children;

        /** Creates one package/container/target node backed by immutable controller data. */
        TargetNode(String displayName, KaMoveNestedMemberComputer.TargetCandidate candidate) {
            this(new Children.Array(), displayName, candidate);
        }

        /** Creates the node after allocating mutable children for the target hierarchy. */
        private TargetNode(Children.Array children, String displayName,
                           KaMoveNestedMemberComputer.TargetCandidate candidate) {
            super(children, candidate == null ? Lookup.EMPTY : Lookups.fixed(candidate));
            this.children = children;
            setDisplayName(displayName);
        }

        /** Appends one child while retaining the controller-determined sort order. */
        void add(TargetNode child) { children.add(new Node[] { child }); }
    }
}
