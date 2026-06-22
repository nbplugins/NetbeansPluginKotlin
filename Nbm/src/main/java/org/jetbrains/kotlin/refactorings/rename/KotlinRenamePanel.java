/**
 * *****************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
 ******************************************************************************
 */
package org.jetbrains.kotlin.refactorings.rename;

import java.awt.Component;
import java.awt.event.ItemEvent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.openide.util.NbPreferences;

/**
 *
 * @author Alexander.Baratynski
 */
public class KotlinRenamePanel extends JPanel implements CustomRefactoringPanel {

    private static final String PREF_SEARCH_IN_COMMENTS = "searchInComments.rename";

    private javax.swing.JPanel jPanel1;
    private javax.swing.JLabel label;
    private javax.swing.JTextField nameField;
    private javax.swing.JCheckBox searchInCommentsCheckBox;
    private final transient String oldName;
    private final transient ChangeListener parent;

    /**
     * Creates a new rename panel pre-filled with {@code oldName}.
     *
     * @param oldName the current symbol name shown in the name field
     * @param parent  the change listener notified when the panel state changes
     */
    public KotlinRenamePanel(String oldName, ChangeListener parent) {
        this.oldName = oldName;
        this.parent = parent;
        initComponents();
        nameField.setEnabled(true);
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void changedUpdate(DocumentEvent event) {
                KotlinRenamePanel.this.parent.stateChanged(null);
            }
            @Override
            public void insertUpdate(DocumentEvent event) {
                KotlinRenamePanel.this.parent.stateChanged(null);
            }
            @Override
            public void removeUpdate(DocumentEvent event) {
                KotlinRenamePanel.this.parent.stateChanged(null);
            }
        });
    }

    /** Returns the new name typed by the user. */
    public String getNameValue() {
        return nameField.getText();
    }

    /** Returns {@code true} if the user wants occurrences in comments to be renamed too. */
    public boolean searchInComments() {
        return searchInCommentsCheckBox.isSelected();
    }
    
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        label = new javax.swing.JLabel();
        nameField = new javax.swing.JTextField();
        searchInCommentsCheckBox = new javax.swing.JCheckBox();
        jPanel1 = new javax.swing.JPanel();

        setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 11, 11));
        setLayout(new java.awt.GridBagLayout());

        label.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        label.setLabelFor(nameField);
        add(label, new java.awt.GridBagConstraints());

        nameField.setText(oldName);
        nameField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent evt) {
                nameFieldFocusLost(evt);
            }
        });

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.weightx = 1.0;
        add(nameField, gridBagConstraints);

        // "Search in comments" checkbox — persisted across IDE sessions.
        searchInCommentsCheckBox.setSelected(
                NbPreferences.forModule(KotlinRenamePanel.class).getBoolean(PREF_SEARCH_IN_COMMENTS, false));
        searchInCommentsCheckBox.setText("Search in &comments");
        org.openide.awt.Mnemonics.setLocalizedText(searchInCommentsCheckBox, "Search in &comments");
        searchInCommentsCheckBox.addItemListener((ItemEvent evt) -> {
            boolean selected = evt.getStateChange() == ItemEvent.SELECTED;
            NbPreferences.forModule(KotlinRenamePanel.class).putBoolean(PREF_SEARCH_IN_COMMENTS, selected);
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.WEST;
        add(searchInCommentsCheckBox, gridBagConstraints);

        jPanel1.setMinimumSize(new java.awt.Dimension(0, 0));
        jPanel1.setPreferredSize(new java.awt.Dimension(0, 0));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.gridheight = 2;
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 1.0;
        add(jPanel1, gridBagConstraints);
    }
    
    
    @Override
    public void initialize() {
        // requestFocusInWindow() here is a no-op when the dialog is not yet visible.
        // addNotify() handles focus after the panel is added to the window hierarchy.
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // invokeLater ensures the dialog is fully visible before requesting focus.
        SwingUtilities.invokeLater(() -> {
            nameField.requestFocusInWindow();
            nameField.setCaretPosition(nameField.getText().length());
        });
    }

    @Override
    public Component getComponent() {
        return this;
    }
    
    private void nameFieldFocusLost(java.awt.event.FocusEvent evt) {                                    
        parent.stateChanged(null);
    } 
    
}