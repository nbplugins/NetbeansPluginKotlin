/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession;
import io.github.nbplugins.kotlin.refactoring.KaPullMembersUpComputer;
import io.github.nbplugins.kotlin.refactoring.PullMembersUpMemberCandidate;
import io.github.nbplugins.kotlin.refactoring.PullMembersUpRequest;
import io.github.nbplugins.kotlin.refactoring.PullMembersUpTarget;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.utils.ProjectUtils;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListCellRenderer;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Refactoring UI for Kotlin Pull Members Up.
 *
 * It lets the user select a direct Kotlin supertype, members, and abstract-member choices. Its
 * conflict-preview button invokes the K2 computer before NetBeans schedules a mutation.
 */
public final class KotlinPullMembersUpUI implements RefactoringUI {
    private final KaPullMembersUpComputer.Discovery.Ready discovery;
    private final KotlinPullMembersUpRefactoring refactoring;
    private PullMembersUpPanel panel;

    /**
     * Creates the UI for one discovered Pull Members Up invocation.
     *
     * @param discovery K2 targets and source members at the invocation caret
     * @param refactoring carrier receiving the selected offsets
     */
    public KotlinPullMembersUpUI(KaPullMembersUpComputer.Discovery.Ready discovery,
                                 KotlinPullMembersUpRefactoring refactoring) {
        this.discovery = discovery;
        this.refactoring = refactoring;
    }

    /** @return localized operation name shown by the NetBeans refactoring window. */
    @Override public String getName() { return "Pull Members Up"; }

    /** @return operation description including the selected target type when available. */
    @Override public String getDescription() { return "Pull Members Up"; }

    /** @return {@code false} because this UI changes source code. */
    @Override public boolean isQuery() { return false; }

    /**
     * Creates the reusable Swing selection panel.
     *
     * @param parent NetBeans listener that revalidates the Refactor button
     * @return the selection panel
     */
    @Override public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) panel = new PullMembersUpPanel(discovery, refactoring, parent);
        return panel;
    }

    /**
     * Copies current UI choices into the typed refactoring carrier.
     *
     * @return validation problem, or {@code null} when choices are complete
     */
    @Override public Problem setParameters() {
        if (panel != null) panel.copyTo(refactoring);
        return checkParameters();
    }

    /**
     * Validates that a target and one or more members are selected.
     *
     * @return fatal problem for invalid UI state, otherwise {@code null}
     */
    @Override public Problem checkParameters() {
        if (panel == null) return null;
        if (panel.targetOffset() < 0) return new Problem(true, "Select a target superclass or interface.");
        if (panel.selectedOffsets().isEmpty()) return new Problem(true, "Select at least one member to pull up.");
        return null;
    }

    /** @return {@code true} because this operation has configurable parameters. */
    @Override public boolean hasParameters() { return true; }

    /** @return refactoring carrier used by the NetBeans plugin factory. */
    @Override public AbstractRefactoring getRefactoring() { return refactoring; }

    /** @return no dedicated help page currently exists. */
    @Override public HelpCtx getHelpCtx() { return null; }

    /** Swing panel that presents targets, member choices, and the conflict preview action. */
    private static final class PullMembersUpPanel implements CustomRefactoringPanel {
        private final JPanel component;
        private final JComboBox<PullMembersUpTarget> targetCombo;
        private final PullMembersUpMemberTableModel memberTableModel;
        private final JTable memberTable;
        private final KotlinPullMembersUpRefactoring refactoring;

        /**
         * Builds the operation controls.
         *
         * @param discovery K2 discovery data displayed by the controls
         * @param refactoring source/refactoring carrier used for conflict checks
         * @param changeListener callback that refreshes NetBeans parameter validation
         */
        PullMembersUpPanel(KaPullMembersUpComputer.Discovery.Ready discovery,
                           KotlinPullMembersUpRefactoring refactoring, ChangeListener changeListener) {
            this.refactoring = refactoring;
            targetCombo = new JComboBox<>(discovery.getTargets().toArray(new PullMembersUpTarget[0]));
            targetCombo.setRenderer(new TargetRenderer());
            memberTableModel = new PullMembersUpMemberTableModel(discovery.getMembers());
            memberTable = new JTable(memberTableModel);
            memberTable.setPreferredScrollableViewportSize(new java.awt.Dimension(500, 160));
            memberTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            JButton conflicts = new JButton("Preview Conflicts");
            conflicts.addActionListener(event -> previewConflicts());

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            GridBagConstraints label = new GridBagConstraints();
            label.anchor = GridBagConstraints.WEST;
            label.insets = new Insets(2, 0, 2, 8);
            GridBagConstraints field = new GridBagConstraints();
            field.fill = GridBagConstraints.HORIZONTAL;
            field.weightx = 1.0;
            field.gridwidth = GridBagConstraints.REMAINDER;
            field.insets = new Insets(2, 0, 2, 0);
            GridBagConstraints span = new GridBagConstraints();
            span.fill = GridBagConstraints.BOTH;
            span.weightx = 1.0;
            span.weighty = 1.0;
            span.gridwidth = GridBagConstraints.REMAINDER;
            span.insets = new Insets(2, 0, 2, 0);
            JLabel targetLabel = new JLabel("Target supertype:");
            targetLabel.setLabelFor(targetCombo);
            form.add(targetLabel, label);
            form.add(targetCombo, field);
            form.add(new JLabel("Members to pull up:"), span);
            form.add(new JScrollPane(memberTable), span);
            form.add(conflicts, span);
            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.CENTER);
            targetCombo.addActionListener(event -> selectionChanged(changeListener));
            memberTableModel.addTableModelListener(event -> selectionChanged(changeListener));
        }

        /** Invalidates prior conflict confirmation after any refactoring choice changes. */
        private void selectionChanged(ChangeListener changeListener) {
            refactoring.setConflictsPreviewed(false);
            changeListener.stateChanged(null);
        }

        /** Copies the controls into the NetBeans refactoring carrier. */
        void copyTo(KotlinPullMembersUpRefactoring target) {
            PullMembersUpTarget selectedTarget = (PullMembersUpTarget) targetCombo.getSelectedItem();
            commitTableEdit();
            target.setTargetOffset(targetOffset());
            target.setTargetFilePath(selectedTarget == null ? "" : selectedTarget.getFilePath());
            target.setSelectedOffsets(selectedOffsets());
            target.setAbstractOffsets(abstractOffsets());
        }

        /** @return selected target PSI offset, or {@code -1} when none is selected. */
        int targetOffset() {
            PullMembersUpTarget target = (PullMembersUpTarget) targetCombo.getSelectedItem();
            return target == null ? -1 : target.getOffset();
        }

        /** @return stable offsets of all source members checked for movement. */
        Set<Integer> selectedOffsets() {
            commitTableEdit();
            return memberTableModel.selectedOffsets();
        }

        /** @return selected member offsets that should become abstract declarations in the target. */
        Set<Integer> abstractOffsets() {
            commitTableEdit();
            return memberTableModel.abstractOffsets();
        }

        /** Commits a checkbox edit before the current row state is read. */
        private void commitTableEdit() {
            if (memberTable.isEditing()) memberTable.getCellEditor().stopCellEditing();
        }

        /** Opens a non-mutating K2 conflict report for the current selection. */
        private void previewConflicts() {
            copyTo(refactoring);
            PullMembersUpRequest request = refactoring.request();
            if (request == null) {
                JOptionPane.showMessageDialog(component, "Select a target and at least one member first.",
                        "Pull Members Up", JOptionPane.WARNING_MESSAGE);
                return;
            }
            StyledDocument document = refactoring.getDocument();
            org.openide.filesystems.FileObject source = ProjectUtils.getFileObjectForDocument(document);
            if (source == null) return;
            org.netbeans.api.project.Project project = ProjectUtils.getKotlinProjectForFileObject(source);
            if (project == null) project = ProjectUtils.getValidProject();
            if (project == null) return;
            KotlinAnalysisAPISession session = KotlinAnalysisAPISession.Companion.getSession(project);
            KtFile file = session.getKtFileForPath(source.getPath());
            org.openide.filesystems.FileObject target = org.openide.filesystems.FileUtil.toFileObject(
                    new java.io.File(request.getTargetFilePath()));
            KtFile targetFile = target == null ? null : session.getKtFileForPath(target.getPath());
            if (file == null || targetFile == null) return;
            KaPullMembersUpComputer.ConflictCheck result = new KaPullMembersUpComputer(
                    file, refactoring.getCaretOffset(), targetFile).checkConflicts(request);
            String message;
            int type;
            if (result instanceof KaPullMembersUpComputer.ConflictCheck.Conflicts) {
                List<io.github.nbplugins.kotlin.refactoring.PullMembersUpConflict> items =
                        ((KaPullMembersUpComputer.ConflictCheck.Conflicts) result).getItems();
                message = items.stream().map(item -> item.getMessage()).reduce("", (left, right) ->
                        left.isEmpty() ? right : left + "\n\n" + right);
                type = JOptionPane.WARNING_MESSAGE;
                refactoring.setConflictsPreviewed(false);
            } else if (result instanceof KaPullMembersUpComputer.ConflictCheck.Clear) {
                message = "No conflicts were found. The refactoring can be applied.";
                type = JOptionPane.INFORMATION_MESSAGE;
                refactoring.setConflictsPreviewed(true);
            } else {
                message = "The current Pull Members Up request is no longer applicable.";
                type = JOptionPane.ERROR_MESSAGE;
                refactoring.setConflictsPreviewed(false);
            }
            JOptionPane.showMessageDialog(component, message, "Pull Members Up Conflict Preview", type);
        }

        /** Requests focus for the first interactive control. */
        @Override public void initialize() { targetCombo.requestFocusInWindow(); }

        /** @return root Swing component supplied to the refactoring dialog. */
        @Override public Component getComponent() { return component; }
    }

    /** Renders a discovered target with its readable Kotlin type name. */
    private static final class TargetRenderer extends JLabel implements ListCellRenderer<PullMembersUpTarget> {
        /** @return component configured for one target-combo row. */
        @Override public Component getListCellRendererComponent(JList<? extends PullMembersUpTarget> list,
                                                                PullMembersUpTarget value, int index,
                                                                boolean selected, boolean focus) {
            setText(value == null ? "" : value.getName());
            setOpaque(true);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }

    /** Editable row model for individual member movement and abstractness choices. */
    private static final class PullMembersUpMemberTableModel extends AbstractTableModel {
        private static final int PULL_UP_COLUMN = 0;
        private static final int MEMBER_COLUMN = 1;
        private static final int MAKE_ABSTRACT_COLUMN = 2;
        private final List<PullMembersUpMemberRow> rows;

        /**
         * Creates one initially selected row per discovered member.
         *
         * @param members K2 candidates from the source class
         */
        PullMembersUpMemberTableModel(List<PullMembersUpMemberCandidate> members) {
            rows = members.stream().map(PullMembersUpMemberRow::new).toList();
        }

        /** @return offsets of rows selected for movement. */
        Set<Integer> selectedOffsets() {
            Set<Integer> result = new LinkedHashSet<>();
            for (PullMembersUpMemberRow row : rows) if (row.pullUp) result.add(row.member.getOffset());
            return result;
        }

        /** @return selected rows that should be moved as abstract declarations. */
        Set<Integer> abstractOffsets() {
            Set<Integer> result = new LinkedHashSet<>();
            for (PullMembersUpMemberRow row : rows) {
                if (row.pullUp && row.makeAbstract) result.add(row.member.getOffset());
            }
            return result;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 3; }

        /** @return table column label. */
        @Override public String getColumnName(int column) {
            return switch (column) {
                case PULL_UP_COLUMN -> "Pull Up";
                case MEMBER_COLUMN -> "Member";
                case MAKE_ABSTRACT_COLUMN -> "Make Abstract";
                default -> "";
            };
        }

        /** @return checkbox type for choice columns and text type for the member label. */
        @Override public Class<?> getColumnClass(int column) {
            return column == MEMBER_COLUMN ? String.class : Boolean.class;
        }

        /** @return whether this table cell can be changed by the user. */
        @Override public boolean isCellEditable(int row, int column) {
            PullMembersUpMemberRow member = rows.get(row);
            if (column == PULL_UP_COLUMN) return member.member.getEnabled();
            return column == MAKE_ABSTRACT_COLUMN && member.member.getEnabled() && member.pullUp;
        }

        /** @return current checkbox state or K2 member presentation. */
        @Override public Object getValueAt(int row, int column) {
            PullMembersUpMemberRow member = rows.get(row);
            return switch (column) {
                case PULL_UP_COLUMN -> member.pullUp;
                case MEMBER_COLUMN -> member.member.getPresentation();
                case MAKE_ABSTRACT_COLUMN -> member.makeAbstract;
                default -> null;
            };
        }

        /** Updates a row choice and ensures an unselected member cannot stay abstract. */
        @Override public void setValueAt(Object value, int row, int column) {
            PullMembersUpMemberRow member = rows.get(row);
            if (column == PULL_UP_COLUMN) {
                member.pullUp = Boolean.TRUE.equals(value);
                if (!member.pullUp) member.makeAbstract = false;
                fireTableRowsUpdated(row, row);
            } else if (column == MAKE_ABSTRACT_COLUMN && member.pullUp) {
                member.makeAbstract = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, column);
            }
        }
    }

    /** Mutable UI state associated with one immutable K2 candidate. */
    private static final class PullMembersUpMemberRow {
        private final PullMembersUpMemberCandidate member;
        private boolean pullUp;
        private boolean makeAbstract;

        /**
         * Creates a selected row with the candidate's initial abstractness choice.
         *
         * @param member immutable K2 source-member candidate
         */
        PullMembersUpMemberRow(PullMembersUpMemberCandidate member) {
            this.member = member;
            pullUp = member.getEnabled();
            makeAbstract = member.getMakeAbstract();
        }
    }
}
