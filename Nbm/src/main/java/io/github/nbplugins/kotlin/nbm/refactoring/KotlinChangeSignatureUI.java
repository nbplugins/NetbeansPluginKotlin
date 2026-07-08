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

import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureParameter;
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureRequest;
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureResult;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * {@link RefactoringUI} adapter for the Kotlin **Change Signature** refactoring (E9.8, M1).
 *
 * Shows a panel with:
 * <ul>
 *   <li>A "Name" text field pre-filled with the current declaration name.</li>
 *   <li>A "Return type" text field pre-filled with the current return type.</li>
 *   <li>A parameter table (name/type columns) with Add/Remove row buttons.</li>
 * </ul>
 *
 * Reordering existing parameter rows (drag-to-reorder / move up-down buttons) is deferred to a
 * later milestone (see {@code docs/development-plan.md}'s E9.8 M2 entry) — this M1 panel supports
 * renaming, adding, and removing parameters, which already exercises the same underlying engine
 * path as reordering.
 *
 * @param initialResult the analysis result from {@link io.github.nbplugins.kotlin.refactoring.KaChangeSignatureComputer}
 * @param refactoring   the carrier {@link KotlinChangeSignatureRefactoring}
 */
public class KotlinChangeSignatureUI implements RefactoringUI {

    private final KotlinChangeSignatureRefactoring refactoring;
    private final KaChangeSignatureResult initialResult;
    private ChangeSignaturePanel panel;

    public KotlinChangeSignatureUI(KaChangeSignatureResult initialResult,
                                    KotlinChangeSignatureRefactoring refactoring) {
        this.initialResult = initialResult;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Change Signature";
    }

    @Override
    public String getDescription() {
        return "Change signature of '" + initialResult.getDeclarationName() + "'";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            panel = new ChangeSignaturePanel(initialResult, parent);
        }
        return panel;
    }

    /**
     * Copies the edited name/return type/parameters into the refactoring carrier.
     *
     * @return {@code null} — validation is deferred to {@link #checkParameters()}
     */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setRequest(panel.buildRequest());
        }
        return null;
    }

    @Override
    public Problem checkParameters() {
        if (panel != null) {
            String name = panel.getNameValue().trim();
            if (name.isEmpty()) {
                return new Problem(true, "Function name must not be empty.");
            }
            for (KaChangeSignatureParameter p : panel.getParameters()) {
                if (p.getName().trim().isEmpty()) {
                    return new Problem(true, "Parameter name must not be empty.");
                }
                if (p.getTypeText().trim().isEmpty()) {
                    return new Problem(true, "Parameter type must not be empty.");
                }
            }
        }
        return null;
    }

    @Override
    public boolean hasParameters() {
        return true;
    }

    @Override
    public AbstractRefactoring getRefactoring() {
        return refactoring;
    }

    @Override
    public HelpCtx getHelpCtx() {
        return null;
    }

    /**
     * Panel with name field, return-type field, and an editable parameter table (name/type
     * columns) with Add/Remove row buttons.
     */
    private static final class ChangeSignaturePanel implements CustomRefactoringPanel {

        private final JPanel component;
        private final JTextField nameField;
        private final JTextField returnTypeField;
        private final ParameterTableModel tableModel;
        private final JTable table;

        ChangeSignaturePanel(KaChangeSignatureResult initialResult, ChangeListener changeListener) {
            nameField = new JTextField(initialResult.getDeclarationName(), 30);
            returnTypeField = new JTextField(initialResult.getReturnTypeText(), 30);
            tableModel = new ParameterTableModel(new ArrayList<>(initialResult.getParameters()));
            table = new JTable(tableModel);
            table.setPreferredScrollableViewportSize(new java.awt.Dimension(400, 120));
            // Swing does not commit an in-progress cell edit when focus moves to a component
            // outside the table (terminateEditOnFocusLost defaults to false) — without this, a
            // user who types a new value and immediately clicks the dialog's "Refactor" button
            // (without pressing Enter/Tab first) silently loses that edit.
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

            GridBagConstraints lc = new GridBagConstraints();
            lc.anchor = GridBagConstraints.WEST;
            lc.insets = new Insets(2, 0, 2, 6);

            GridBagConstraints fc = new GridBagConstraints();
            fc.fill = GridBagConstraints.HORIZONTAL;
            fc.weightx = 1.0;
            fc.gridwidth = GridBagConstraints.REMAINDER;
            fc.insets = new Insets(2, 0, 2, 0);

            JLabel nameLabel = new JLabel("Name:");
            nameLabel.setLabelFor(nameField);
            form.add(nameLabel, lc);
            form.add(nameField, fc);

            JLabel returnTypeLabel = new JLabel("Return type:");
            returnTypeLabel.setLabelFor(returnTypeField);
            form.add(returnTypeLabel, lc);
            form.add(returnTypeField, fc);

            JLabel parametersLabel = new JLabel("Parameters:");
            GridBagConstraints plc = new GridBagConstraints();
            plc.anchor = GridBagConstraints.NORTHWEST;
            plc.insets = new Insets(6, 0, 2, 6);
            form.add(parametersLabel, plc);

            JPanel tablePanel = new JPanel(new BorderLayout());
            tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);

            JButton addButton = new JButton(new AbstractAction("Add") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    tableModel.addRow();
                    changeListener.stateChanged(null);
                }
            });
            JButton removeButton = new JButton(new AbstractAction("Remove") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        tableModel.removeRow(row);
                        changeListener.stateChanged(null);
                    }
                }
            });
            JPanel buttons = new JPanel();
            buttons.add(addButton);
            buttons.add(removeButton);
            tablePanel.add(buttons, BorderLayout.SOUTH);

            GridBagConstraints tc = new GridBagConstraints();
            tc.fill = GridBagConstraints.BOTH;
            tc.weightx = 1.0;
            tc.weighty = 1.0;
            tc.gridwidth = GridBagConstraints.REMAINDER;
            tc.insets = new Insets(6, 0, 2, 0);
            form.add(tablePanel, tc);

            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.CENTER);

            DocumentListener listener = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e)  { changeListener.stateChanged(null); }
                @Override public void removeUpdate(DocumentEvent e)  { changeListener.stateChanged(null); }
                @Override public void changedUpdate(DocumentEvent e) { changeListener.stateChanged(null); }
            };
            nameField.getDocument().addDocumentListener(listener);
            returnTypeField.getDocument().addDocumentListener(listener);
            tableModel.addTableModelListener(e -> changeListener.stateChanged(null));
        }

        /** Returns the current text in the name field. */
        String getNameValue() { return nameField.getText(); }

        /**
         * Returns the current parameter rows, first committing any in-progress cell edit (belt
         * and suspenders alongside {@code terminateEditOnFocusLost} above).
         */
        List<KaChangeSignatureParameter> getParameters() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            return tableModel.getParameters();
        }

        /** Builds the [KaChangeSignatureRequest] the engine will apply. */
        KaChangeSignatureRequest buildRequest() {
            return new KaChangeSignatureRequest(getNameValue(), returnTypeField.getText(), getParameters());
        }

        @Override public void initialize() { nameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }

    /**
     * Editable table model over a mutable list of {@link KaChangeSignatureParameter} (name/type
     * columns). A brand-new row (added via the "Add" button) gets {@code originalIndex = -1}, per
     * {@link KaChangeSignatureParameter}'s "new parameter" convention.
     */
    private static final class ParameterTableModel extends AbstractTableModel {

        private final List<KaChangeSignatureParameter> parameters;

        ParameterTableModel(List<KaChangeSignatureParameter> parameters) {
            this.parameters = parameters;
        }

        List<KaChangeSignatureParameter> getParameters() { return parameters; }

        void addRow() {
            parameters.add(new KaChangeSignatureParameter(-1, "newParam", "Any"));
            fireTableRowsInserted(parameters.size() - 1, parameters.size() - 1);
        }

        void removeRow(int row) {
            parameters.remove(row);
            fireTableRowsDeleted(row, row);
        }

        @Override public int getRowCount() { return parameters.size(); }
        @Override public int getColumnCount() { return 2; }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Name" : "Type";
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) { return true; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            KaChangeSignatureParameter p = parameters.get(rowIndex);
            return columnIndex == 0 ? p.getName() : p.getTypeText();
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            KaChangeSignatureParameter p = parameters.get(rowIndex);
            String text = String.valueOf(value);
            parameters.set(rowIndex, columnIndex == 0
                    ? p.copy(p.getOriginalIndex(), text, p.getTypeText())
                    : p.copy(p.getOriginalIndex(), p.getName(), text));
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
