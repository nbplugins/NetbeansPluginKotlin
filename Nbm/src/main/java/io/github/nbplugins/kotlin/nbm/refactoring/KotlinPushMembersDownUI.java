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

import io.github.nbplugins.kotlin.refactoring.KaPushMembersDownComputer;
import io.github.nbplugins.kotlin.refactoring.PushMembersDownMemberCandidate;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Refactoring UI for selecting Kotlin members to push into all direct subclasses. */
public final class KotlinPushMembersDownUI implements RefactoringUI {
    private final KaPushMembersDownComputer.Discovery.Ready discovery;
    private final KotlinPushMembersDownRefactoring refactoring;
    private PushMembersDownPanel panel;

    /**
     * Creates a Push Members Down UI.
     *
     * @param discovery members resolved from the source Kotlin class.
     * @param refactoring carrier receiving table selections.
     */
    public KotlinPushMembersDownUI(KaPushMembersDownComputer.Discovery.Ready discovery,
                                   KotlinPushMembersDownRefactoring refactoring) {
        this.discovery = discovery;
        this.refactoring = refactoring;
    }

    /** @return operation label displayed by NetBeans. */
    @Override public String getName() { return "Push Members Down"; }
    /** @return operation description. */
    @Override public String getDescription() { return "Push Members Down into direct Kotlin subclasses"; }
    /** @return {@code false} because this UI changes source. */
    @Override public boolean isQuery() { return false; }

    /** @return the member-selection panel. */
    @Override public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) panel = new PushMembersDownPanel(discovery, refactoring, parent);
        return panel;
    }

    /** @return validation result after copying current selections to the carrier. */
    @Override public Problem setParameters() {
        if (panel != null) panel.copyTo(refactoring);
        return checkParameters();
    }

    /** @return a fatal problem when no member is selected. */
    @Override public Problem checkParameters() {
        if (panel != null && panel.selectedOffsets().isEmpty()) {
            return new Problem(true, "Select at least one member to push down.");
        }
        return null;
    }

    /** @return {@code true}; member selection and abstractness are configurable. */
    @Override public boolean hasParameters() { return true; }
    /** @return NetBeans refactoring carrier. */
    @Override public AbstractRefactoring getRefactoring() { return refactoring; }
    /** @return no dedicated help page. */
    @Override public HelpCtx getHelpCtx() { return null; }

    /** Swing panel containing member and per-member Make Abstract controls. */
    private static final class PushMembersDownPanel implements CustomRefactoringPanel {
        private final JPanel component;
        private final JTable memberTable;
        private final PushMembersDownMemberTableModel model;
        private final KotlinPushMembersDownRefactoring refactoring;

        /**
         * Creates the editable member table.
         *
         * @param discovery source member candidates.
         * @param refactoring carrier used by NetBeans.
         * @param listener callback used to refresh parameter validity.
         */
        PushMembersDownPanel(KaPushMembersDownComputer.Discovery.Ready discovery,
                             KotlinPushMembersDownRefactoring refactoring,
                             ChangeListener listener) {
            this.refactoring = refactoring;
            model = new PushMembersDownMemberTableModel(discovery.getMembers());
            memberTable = new JTable(model);
            memberTable.setPreferredScrollableViewportSize(new java.awt.Dimension(500, 160));
            memberTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
            model.addTableModelListener(event -> {
                refactoring.setSelectedOffsets(selectedOffsets());
                refactoring.setAbstractOffsets(abstractOffsets());
                listener.stateChanged(null);
            });

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            GridBagConstraints span = new GridBagConstraints();
            span.fill = GridBagConstraints.BOTH;
            span.weightx = 1.0;
            span.weighty = 1.0;
            span.gridwidth = GridBagConstraints.REMAINDER;
            span.insets = new Insets(2, 0, 2, 0);
            form.add(new JLabel("Members to push into all direct subclasses:"), span);
            form.add(new JScrollPane(memberTable), span);
            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.CENTER);
        }

        /** Copies table choices into [target]. */
        void copyTo(KotlinPushMembersDownRefactoring target) {
            commitTableEdit();
            target.setSelectedOffsets(selectedOffsets());
            target.setAbstractOffsets(abstractOffsets());
        }

        /** @return stable offsets selected for Push Down. */
        Set<Integer> selectedOffsets() {
            return model.selectedOffsets();
        }

        /** @return selected offsets retained as abstract source declarations. */
        Set<Integer> abstractOffsets() {
            return model.abstractOffsets();
        }

        /** Commits an active checkbox edit before reading model values. */
        private void commitTableEdit() {
            if (memberTable.isEditing()) memberTable.getCellEditor().stopCellEditing();
        }

        /** Requests focus on the selection table. */
        @Override public void initialize() { memberTable.requestFocusInWindow(); }
        /** @return root component. */
        @Override public Component getComponent() { return component; }
    }

    /** Editable row model for Push Down and Make Abstract choices. */
    private static final class PushMembersDownMemberTableModel extends AbstractTableModel {
        private static final int PUSH_DOWN_COLUMN = 0;
        private static final int MEMBER_COLUMN = 1;
        private static final int MAKE_ABSTRACT_COLUMN = 2;
        private final List<PushMembersDownMemberRow> rows;

        /** @param members discovered source members. */
        PushMembersDownMemberTableModel(List<PushMembersDownMemberCandidate> members) {
            rows = members.stream().map(PushMembersDownMemberRow::new).toList();
        }

        /** @return checked source offsets. */
        Set<Integer> selectedOffsets() {
            Set<Integer> result = new LinkedHashSet<>();
            for (PushMembersDownMemberRow row : rows) if (row.pushDown) result.add(row.member.getOffset());
            return result;
        }

        /** @return checked offsets whose source members stay abstract. */
        Set<Integer> abstractOffsets() {
            Set<Integer> result = new LinkedHashSet<>();
            for (PushMembersDownMemberRow row : rows) if (row.pushDown && row.makeAbstract) result.add(row.member.getOffset());
            return result;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 3; }
        /** @return column title. */
        @Override public String getColumnName(int column) {
            return switch (column) {
                case PUSH_DOWN_COLUMN -> "Push Down";
                case MEMBER_COLUMN -> "Member";
                case MAKE_ABSTRACT_COLUMN -> "Make Abstract";
                default -> "";
            };
        }
        /** @return value class for each table column. */
        @Override public Class<?> getColumnClass(int column) {
            return column == MEMBER_COLUMN ? String.class : Boolean.class;
        }
        /** @return whether a control cell can be changed. */
        @Override public boolean isCellEditable(int row, int column) {
            return column == PUSH_DOWN_COLUMN || (column == MAKE_ABSTRACT_COLUMN && rows.get(row).pushDown);
        }
        /** @return current row value. */
        @Override public Object getValueAt(int row, int column) {
            PushMembersDownMemberRow member = rows.get(row);
            return switch (column) {
                case PUSH_DOWN_COLUMN -> member.pushDown;
                case MEMBER_COLUMN -> member.member.getPresentation();
                case MAKE_ABSTRACT_COLUMN -> member.makeAbstract;
                default -> null;
            };
        }
        /** Updates one selection and clears abstractness when the member is deselected. */
        @Override public void setValueAt(Object value, int row, int column) {
            PushMembersDownMemberRow member = rows.get(row);
            if (column == PUSH_DOWN_COLUMN) {
                member.pushDown = Boolean.TRUE.equals(value);
                if (!member.pushDown) member.makeAbstract = false;
                fireTableRowsUpdated(row, row);
            } else if (column == MAKE_ABSTRACT_COLUMN && member.pushDown) {
                member.makeAbstract = Boolean.TRUE.equals(value);
                fireTableCellUpdated(row, column);
            }
        }
    }

    /** Mutable UI state for a discovered member. */
    private static final class PushMembersDownMemberRow {
        private final PushMembersDownMemberCandidate member;
        private boolean pushDown = true;
        private boolean makeAbstract;

        /** @param member immutable K2 candidate. */
        PushMembersDownMemberRow(PushMembersDownMemberCandidate member) {
            this.member = member;
        }
    }
}
