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
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionListener;
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
 *   <li>A parameter table (name/type columns) with Add/Remove/Move Up/Move Down buttons, and
 *       drag-and-drop row reordering.</li>
 * </ul>
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
            table.setDragEnabled(true);
            table.setDropMode(DropMode.INSERT_ROWS);
            table.setTransferHandler(new RowReorderTransferHandler(tableModel, table));

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
            JButton moveUpButton = new JButton(new AbstractAction("Move Up") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    int row = table.getSelectedRow();
                    if (row > 0) {
                        tableModel.moveRow(row, row - 1);
                        table.setRowSelectionInterval(row - 1, row - 1);
                        changeListener.stateChanged(null);
                    }
                }
            });
            JButton moveDownButton = new JButton(new AbstractAction("Move Down") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    int row = table.getSelectedRow();
                    if (row >= 0 && row < tableModel.getRowCount() - 1) {
                        tableModel.moveRow(row, row + 1);
                        table.setRowSelectionInterval(row + 1, row + 1);
                        changeListener.stateChanged(null);
                    }
                }
            });
            Runnable updateMoveButtons = () -> {
                int row = table.getSelectedRow();
                moveUpButton.setEnabled(row > 0);
                moveDownButton.setEnabled(row >= 0 && row < tableModel.getRowCount() - 1);
            };
            ListSelectionListener selectionListener = e -> updateMoveButtons.run();
            table.getSelectionModel().addListSelectionListener(selectionListener);
            tableModel.addTableModelListener(e -> updateMoveButtons.run());
            updateMoveButtons.run();

            JPanel buttons = new JPanel();
            buttons.add(addButton);
            buttons.add(removeButton);
            buttons.add(moveUpButton);
            buttons.add(moveDownButton);
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

        /** Moves the row at [from] to index [to], shifting the rows in between. */
        void moveRow(int from, int to) {
            if (from == to) return;
            KaChangeSignatureParameter p = parameters.remove(from);
            parameters.add(to, p);
            fireTableRowsUpdated(Math.min(from, to), Math.max(from, to));
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

    /**
     * Enables drag-and-drop reordering of {@link ParameterTableModel} rows within [table] — the
     * standard Swing recipe (a {@link TransferHandler} moving a single row index, imported via a
     * private {@link DataFlavor}) since {@link JTable} has no built-in row-reorder support outside
     * {@code JXTable}-style third-party components.
     */
    private static final class RowReorderTransferHandler extends TransferHandler {

        private static final DataFlavor ROW_INDEX_FLAVOR =
                new DataFlavor(Integer.class, "Change Signature parameter row index");

        private final ParameterTableModel tableModel;
        private final JTable table;

        RowReorderTransferHandler(ParameterTableModel tableModel, JTable table) {
            this.tableModel = tableModel;
            this.table = table;
        }

        @Override
        public int getSourceActions(JComponent c) {
            return TransferHandler.MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            int row = table.getSelectedRow();
            return new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{ROW_INDEX_FLAVOR}; }
                @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return ROW_INDEX_FLAVOR.equals(flavor); }
                @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
                    return row;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(ROW_INDEX_FLAVOR);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                int fromRow = (Integer) support.getTransferable().getTransferData(ROW_INDEX_FLAVOR);
                int toRow = ((JTable.DropLocation) support.getDropLocation()).getRow();
                if (toRow < 0) toRow = tableModel.getRowCount() - 1;
                if (toRow > fromRow) toRow--; // account for the removed source row shifting later indices down
                toRow = Math.max(0, Math.min(toRow, tableModel.getRowCount() - 1));
                if (fromRow == toRow) return false;
                tableModel.moveRow(fromRow, toRow);
                table.setRowSelectionInterval(toRow, toRow);
                return true;
            } catch (UnsupportedFlavorException | IOException e) {
                return false;
            }
        }
    }
}
