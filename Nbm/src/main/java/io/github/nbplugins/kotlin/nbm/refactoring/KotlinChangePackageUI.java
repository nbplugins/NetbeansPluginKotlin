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
import javax.swing.JCheckBox;
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
 * Configures the package and supported-reference update option for Change Kotlin Package.
 *
 * @param refactoring mutable carrier receiving user-selected settings
 * @param target package validator and source-root context
 * @param initialPackage initial package text shown to the user
 */
public final class KotlinChangePackageUI implements RefactoringUI {

    private final KotlinChangePackageRefactoring refactoring;
    private final KotlinPackageTarget target;
    private final String initialPackage;
    private ChangePackagePanel panel;

    /**
     * Creates the Change Kotlin Package UI.
     *
     * @param refactoring mutable carrier receiving user-selected settings
     * @param target package validator and source-root context
     * @param initialPackage initial package text shown to the user
     */
    public KotlinChangePackageUI(KotlinChangePackageRefactoring refactoring,
                                 KotlinPackageTarget target,
                                 String initialPackage) {
        this.refactoring = refactoring;
        this.target = target;
        this.initialPackage = initialPackage;
    }

    /** @return the user-visible refactoring name. */
    @Override
    public String getName() {
        return "Change Kotlin Package";
    }

    /** @return concise description of the selected file or directory. */
    @Override
    public String getDescription() {
        if (refactoring.getSelection().isDirectory()) {
            return "Change package of " + refactoring.getSelection().getSourceFiles().size()
                    + " Kotlin files in directory '" + refactoring.getSelection().getDisplayName() + "'";
        }
        return "Change package of Kotlin file '" + refactoring.getSelection().getDisplayName() + "'";
    }

    /** @return false because the operation mutates Kotlin source documents. */
    @Override
    public boolean isQuery() {
        return false;
    }

    /**
     * Creates the package-change form on first use.
     *
     * @param parent listener notified when values change
     * @return cached package-change form
     */
    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            panel = new ChangePackagePanel(
                    refactoring.getSelection().getDisplayName(),
                    refactoring.getSelection().getSourceFiles().size(),
                    refactoring.getSelection().isDirectory(),
                    initialPackage,
                    parent
            );
        }
        return panel;
    }

    /**
     * Copies selected settings to the carrier.
     *
     * @return a fatal problem for an invalid package, otherwise {@code null}
     */
    @Override
    public Problem setParameters() {
        if (panel == null) {
            return null;
        }
        String packageName = panel.getPackageValue().trim();
        if (!target.isValidPackage(packageName)) {
            return new Problem(true, "Target package is not a valid Kotlin package name.");
        }
        refactoring.setTargetPackage(packageName);
        refactoring.setUpdateReferences(panel.isUpdateReferences());
        return null;
    }

    /**
     * Validates current form values before NetBeans schedules the operation.
     *
     * @return fatal validation problem, or {@code null}
     */
    @Override
    public Problem checkParameters() {
        return setParameters();
    }

    /** @return true because Change Package has a configuration form. */
    @Override
    public boolean hasParameters() {
        return true;
    }

    /** @return carrier supplied to NetBeans refactoring infrastructure. */
    @Override
    public AbstractRefactoring getRefactoring() {
        return refactoring;
    }

    /** @return no dedicated help page. */
    @Override
    public HelpCtx getHelpCtx() {
        return null;
    }

    /** Swing form for Change Kotlin Package parameters. */
    private static final class ChangePackagePanel implements CustomRefactoringPanel {
        private final JPanel component;
        private final JTextField packageField;
        private final JCheckBox updateReferences;

        /**
         * Builds the package-change form.
         *
         * @param name displayed source file or directory name
         * @param sourceCount selected Kotlin file count
         * @param directorySelection whether the selection originated from a directory
         * @param initialPackage initially displayed package
         * @param changeListener listener notified of UI changes
         */
        ChangePackagePanel(String name, int sourceCount, boolean directorySelection, String initialPackage,
                           ChangeListener changeListener) {
            packageField = new JTextField(initialPackage, 30);
            updateReferences = new JCheckBox("Update Kotlin references", true);
            updateReferences.setToolTipText("Updates supported Kotlin code references; Java, comments, and text are not searched.");
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
            form.add(new JLabel(directorySelection ? "Kotlin directory:" : "Kotlin file:"), label);
            form.add(new JLabel(directorySelection ? name + " (" + sourceCount + " Kotlin files)" : name), field);
            JLabel packageLabel = new JLabel("New package:");
            packageLabel.setLabelFor(packageField);
            form.add(packageLabel, label);
            form.add(packageField, field);
            form.add(new JLabel(""), label);
            form.add(updateReferences, field);
            form.add(new JLabel(""), label);
            String note = directorySelection
                    ? "Nested Kotlin files retain their relative subfolder hierarchy; physical locations remain unchanged."
                    : "The file's physical location remains unchanged.";
            form.add(new JLabel(note), field);
            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.NORTH);
            DocumentListener listener = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
                @Override public void removeUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
                @Override public void changedUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
            };
            packageField.getDocument().addDocumentListener(listener);
            updateReferences.addActionListener(event -> changeListener.stateChanged(null));
        }

        /** @return current target-package text. */
        String getPackageValue() {
            return packageField.getText();
        }

        /** @return whether supported external Kotlin references should be retargeted. */
        boolean isUpdateReferences() {
            return updateReferences.isSelected();
        }

        /** Requests initial focus for the package field. */
        @Override
        public void initialize() {
            packageField.requestFocusInWindow();
        }

        /** @return component displayed by NetBeans. */
        @Override
        public Component getComponent() {
            return component;
        }
    }
}
