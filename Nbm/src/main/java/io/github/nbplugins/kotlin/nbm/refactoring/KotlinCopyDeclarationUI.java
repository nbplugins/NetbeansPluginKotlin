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
package io.github.nbplugins.kotlin.nbm.refactoring;

import io.github.nbplugins.kotlin.refactoring.KaCopyDeclarationResult;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * {@link RefactoringUI} adapter for Kotlin {@code Copy Declaration}.
 *
 * The panel lets the user select a project source root, an existing or new Kotlin package, and a
 * target file name. The target is initially the source file's root and package.
 *
 * @param initialResult analysis result for the declaration being copied
 * @param refactoring carrier which receives destination choices
 * @param target available source roots and package-directory resolver
 */
public class KotlinCopyDeclarationUI implements RefactoringUI {

    private final KotlinCopyDeclarationRefactoring refactoring;
    private final KaCopyDeclarationResult initialResult;
    private final KotlinPackageTarget target;
    private CopyPanel panel;

    /**
     * Creates the Copy Declaration refactoring user interface.
     *
     * @param initialResult analysis result containing the declaration name and suggested filename
     * @param refactoring carrier refactoring object
     * @param target source-root and package target model
     */
    public KotlinCopyDeclarationUI(KaCopyDeclarationResult initialResult,
                                   KotlinCopyDeclarationRefactoring refactoring,
                                   KotlinPackageTarget target) {
        this.initialResult = initialResult;
        this.refactoring = refactoring;
        this.target = target;
    }

    @Override public String getName() { return "Copy Declaration"; }

    @Override
    public String getDescription() {
        return "Copy declaration '" + initialResult.getDeclarationName() + "' to another file/package";
    }

    @Override public boolean isQuery() { return false; }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            panel = new CopyPanel(initialResult.getDeclarationName(), initialResult.getSuggestedFileName(), target, parent);
        }
        return panel;
    }

    /** Copies the selected destination into the refactoring carrier. */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setTargetRootPath(panel.targetRootPath());
            refactoring.setTargetPackage(panel.targetPackage());
            refactoring.setTargetFileName(panel.targetFileName());
        }
        return checkParameters();
    }

    /** Validates the destination choices required by the standalone target-file integration. */
    @Override
    public Problem checkParameters() {
        if (panel == null) return null;
        if (panel.targetRootPath() == null) return new Problem(true, "Select a target source root.");
        if (!target.isValidPackage(panel.targetPackage().trim())) {
            return new Problem(true, "Target package must be a valid Kotlin package name.");
        }
        if (panel.targetFileName().trim().isEmpty()) {
            return new Problem(true, "Target Kotlin file name must not be empty.");
        }
        return null;
    }

    @Override public boolean hasParameters() { return true; }
    @Override public AbstractRefactoring getRefactoring() { return refactoring; }
    @Override public HelpCtx getHelpCtx() { return null; }

    /** Destination selector panel. */
    private static final class CopyPanel implements CustomRefactoringPanel {
        private final JPanel component;
        private final JComboBox<KotlinPackageTargetRoot> rootCombo;
        private final JComboBox<String> packageCombo;
        private final JTextField fileNameField;

        CopyPanel(String declarationName, String suggestedFileName, KotlinPackageTarget target,
                  ChangeListener changeListener) {
            rootCombo = new JComboBox<>(target.getRoots().toArray(new KotlinPackageTargetRoot[0]));
            rootCombo.setRenderer(new RootRenderer());
            selectDefaultRoot(target);
            packageCombo = new JComboBox<>();
            packageCombo.setEditable(true);
            updatePackages(target);
            fileNameField = new JTextField(suggestedFileName, 30);
            fileNameField.selectAll();

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

            form.add(new JLabel("Declaration:"), label);
            form.add(new JLabel(declarationName), field);
            JLabel rootLabel = new JLabel("Target source root:");
            rootLabel.setLabelFor(rootCombo);
            form.add(rootLabel, label);
            form.add(rootCombo, field);
            JLabel packageLabel = new JLabel("Target package:");
            packageLabel.setLabelFor(packageCombo);
            form.add(packageLabel, label);
            form.add(packageCombo, field);
            JLabel fileLabel = new JLabel("Target file name:");
            fileLabel.setLabelFor(fileNameField);
            form.add(fileLabel, label);
            form.add(fileNameField, field);

            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.NORTH);
            DocumentListener listener = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
                @Override public void removeUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
                @Override public void changedUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
            };
            ((JTextField) packageCombo.getEditor().getEditorComponent()).getDocument().addDocumentListener(listener);
            fileNameField.getDocument().addDocumentListener(listener);
            rootCombo.addActionListener(event -> {
                updatePackages(target);
                changeListener.stateChanged(null);
            });
            packageCombo.addActionListener(event -> changeListener.stateChanged(null));
        }

        /** @return selected source-root path, or {@code null} if unavailable. */
        String targetRootPath() {
            KotlinPackageTargetRoot root = (KotlinPackageTargetRoot) rootCombo.getSelectedItem();
            return root == null ? null : root.getPath();
        }

        /** @return selected or entered target package. */
        String targetPackage() {
            Object item = packageCombo.getEditor().getItem();
            return item == null ? "" : item.toString();
        }

        /** @return target Kotlin filename. */
        String targetFileName() { return fileNameField.getText(); }

        /** Selects the root containing the source file. */
        private void selectDefaultRoot(KotlinPackageTarget target) {
            String defaultRoot = target.getDefaultRootPath();
            if (defaultRoot == null) return;
            for (int index = 0; index < rootCombo.getItemCount(); index++) {
                if (defaultRoot.equals(rootCombo.getItemAt(index).getPath())) {
                    rootCombo.setSelectedIndex(index);
                    return;
                }
            }
        }

        /** Refreshes package choices when the selected source root changes. */
        private void updatePackages(KotlinPackageTarget target) {
            String selectedPackage = targetPackage();
            if (selectedPackage.isEmpty()) selectedPackage = target.getDefaultPackage();
            packageCombo.setModel(new DefaultComboBoxModel<>(
                    target.packages(targetRootPath()).toArray(new String[0])));
            packageCombo.setEditable(true);
            packageCombo.getEditor().setItem(selectedPackage);
        }

        @Override public void initialize() { fileNameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }

    /** Renders source roots by their project-visible display names. */
    private static final class RootRenderer extends JLabel implements ListCellRenderer<KotlinPackageTargetRoot> {
        @Override
        public Component getListCellRendererComponent(JList<? extends KotlinPackageTargetRoot> list,
                                                      KotlinPackageTargetRoot value, int index,
                                                      boolean selected, boolean focus) {
            setText(value == null ? "" : value.getDisplayName());
            setOpaque(true);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
