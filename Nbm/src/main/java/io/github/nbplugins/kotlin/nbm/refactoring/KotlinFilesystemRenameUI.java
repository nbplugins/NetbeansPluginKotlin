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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * Configures an explicit physical Kotlin file or package-directory rename.
 *
 * @param refactoring mutable typed rename carrier
 * @param directory whether the selected source is a directory rather than one Kotlin file
 * @param displayName selected source name shown in the panel
 */
public final class KotlinFilesystemRenameUI implements RefactoringUI {

    private final AbstractRefactoring refactoring;
    private final boolean directory;
    private final String displayName;
    private RenamePanel panel;

    /**
     * Creates a filesystem rename form.
     *
     * @param refactoring typed file or directory carrier
     * @param directory whether this renames a package directory
     * @param displayName source name initially displayed to the user
     */
    public KotlinFilesystemRenameUI(AbstractRefactoring refactoring, boolean directory, String displayName) {
        this.refactoring = refactoring;
        this.directory = directory;
        this.displayName = displayName;
    }

    /** @return user-visible operation title. */
    @Override
    public String getName() {
        return directory ? "Rename Kotlin Package Directory" : "Rename Kotlin File";
    }

    /** @return concise description of the physical source to rename. */
    @Override
    public String getDescription() {
        return (directory ? "Rename Kotlin package directory '" : "Rename Kotlin file '") + displayName + "'";
    }

    /** @return false because the operation changes project files. */
    @Override
    public boolean isQuery() {
        return false;
    }

    /**
     * Creates the rename form on first use.
     *
     * @param parent listener notified after a field or option changes
     * @return cached configuration panel
     */
    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            String currentName = directory
                    ? ((KotlinRenameDirectoryRefactoring) refactoring).getNewName()
                    : ((KotlinRenameFileRefactoring) refactoring).getNewName();
            panel = new RenamePanel(directory, displayName, currentName, parent);
        }
        return panel;
    }

    /**
     * Copies and validates panel settings into the typed carrier.
     *
     * @return a fatal problem for an empty or invalid filesystem name, otherwise {@code null}
     */
    @Override
    public Problem setParameters() {
        if (panel == null) {
            return null;
        }
        String newName = panel.getNewName().trim();
        if (!newName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return new Problem(true, "The new Kotlin file or package-directory name must be a Kotlin identifier.");
        }
        if (directory) {
            ((KotlinRenameDirectoryRefactoring) refactoring).setNewName(newName);
        } else {
            ((KotlinRenameFileRefactoring) refactoring).setNewName(newName);
        }
        return null;
    }

    /** @return current validation result. */
    @Override
    public Problem checkParameters() {
        return setParameters();
    }

    /** @return true because physical rename requires a target name. */
    @Override
    public boolean hasParameters() {
        return true;
    }

    /** @return the typed refactoring handled by this UI. */
    @Override
    public AbstractRefactoring getRefactoring() {
        return refactoring;
    }

    /** @return no dedicated help page. */
    @Override
    public HelpCtx getHelpCtx() {
        return null;
    }

    /** Swing parameters panel shared by file and directory rename. */
    private static final class RenamePanel implements CustomRefactoringPanel {
        private final JPanel component;
        private final JTextField nameField;

        /**
         * Builds the form.
         *
         * @param directory whether the form represents a package directory
         * @param displayName source name shown to the user
         * @param currentName initial basename or folder name
         * @param listener recipient of state-change notifications
         */
        RenamePanel(boolean directory, String displayName, String currentName, ChangeListener listener) {
            nameField = new JTextField(currentName, 30);
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            GridBagConstraints label = new GridBagConstraints();
            label.anchor = GridBagConstraints.WEST;
            label.insets = new Insets(2, 0, 2, 6);
            GridBagConstraints field = new GridBagConstraints();
            field.fill = GridBagConstraints.HORIZONTAL;
            field.weightx = 1.0;
            field.gridwidth = GridBagConstraints.REMAINDER;
            field.insets = new Insets(2, 0, 2, 0);
            form.add(new JLabel(directory ? "Kotlin package directory:" : "Kotlin file:"), label);
            form.add(new JLabel(displayName), field);
            JLabel nameLabel = new JLabel(directory ? "New directory name:" : "New file name:");
            nameLabel.setLabelFor(nameField);
            form.add(nameLabel, label);
            form.add(nameField, field);
            form.add(new JLabel(""), label);
            form.add(
                    new JLabel(directory
                            ? "The directory and its descendants are renamed; Kotlin packages and references are unchanged."
                            : "The .kt basename changes; packages and Java file-facade references are unchanged."),
                    field
            );
            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.NORTH);
            DocumentListener documentListener = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { listener.stateChanged(null); }
                @Override public void removeUpdate(DocumentEvent event) { listener.stateChanged(null); }
                @Override public void changedUpdate(DocumentEvent event) { listener.stateChanged(null); }
            };
            nameField.getDocument().addDocumentListener(documentListener);
        }

        /** @return requested basename or folder name. */
        String getNewName() {
            return nameField.getText();
        }

        /** Requests initial focus for the editable name. */
        @Override
        public void initialize() {
            nameField.requestFocusInWindow();
            nameField.selectAll();
        }

        /** @return component displayed by NetBeans. */
        @Override
        public Component getComponent() {
            return component;
        }
    }
}
