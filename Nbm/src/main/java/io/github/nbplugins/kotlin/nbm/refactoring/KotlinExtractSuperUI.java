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

import io.github.nbplugins.kotlin.refactoring.ExtractSuperMemberCandidate;
import io.github.nbplugins.kotlin.refactoring.KaExtractSuperComputer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
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
 * Refactoring UI for Kotlin Extract Interface and Extract Superclass.
 *
 * It collects the extracted type name, destination Kotlin filename, selected members, and abstract
 * member choices. The semantic extraction itself remains delegated to the copied IDEA K2 engine.
 */
public final class KotlinExtractSuperUI implements RefactoringUI {
    private final KaExtractSuperComputer.Discovery.Ready discovery;
    private final KotlinExtractSuperRefactoring refactoring;
    private final KotlinPackageTarget target;
    private final String label;
    private ExtractSuperPanel panel;

    /**
     * Creates a UI adapter for one Extract Super invocation.
     *
     * @param discovery IDEA member candidates resolved at the editor caret
     * @param refactoring carrier that receives user choices
     * @param label visible mode label
     */
    public KotlinExtractSuperUI(KaExtractSuperComputer.Discovery.Ready discovery,
                                KotlinExtractSuperRefactoring refactoring,
                                KotlinPackageTarget target, String label) {
        this.discovery = discovery;
        this.refactoring = refactoring;
        this.target = target;
        this.label = label;
    }

    @Override public String getName() { return label; }
    @Override public String getDescription() { return label + " from " + discovery.getSourceName(); }
    @Override public boolean isQuery() { return false; }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            panel = new ExtractSuperPanel(discovery.getMembers(), defaultName(), target, parent);
        }
        return panel;
    }

    /** Copies all dialog choices into the typed carrier before preview/refactoring. */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setExtractedName(panel.name());
            refactoring.setTargetRootPath(panel.targetRootPath());
            refactoring.setTargetPackage(panel.targetPackage());
            refactoring.setTargetFileName(panel.targetFile());
            refactoring.setSelectedOffsets(panel.selectedOffsets());
            refactoring.setAbstractOffsets(panel.abstractOffsets());
        }
        return checkParameters();
    }

    /** Validates only choices that the standalone target-file integration requires. */
    @Override
    public Problem checkParameters() {
        if (panel == null) return null;
        if (panel.name().trim().isEmpty()) return new Problem(true, "Extracted type name must not be empty.");
        if (!panel.name().trim().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return new Problem(true, "Extracted type name must be a valid Kotlin identifier.");
        }
        if (panel.targetRootPath() == null) return new Problem(true, "Select a target source root.");
        if (!target.isValidPackage(panel.targetPackage().trim())) {
            return new Problem(true, "Target package must be a valid Kotlin package name.");
        }
        if (panel.targetFile().trim().isEmpty()) return new Problem(true, "Target Kotlin file name must not be empty.");
        if (panel.selectedOffsets().isEmpty()) return new Problem(true, "Select at least one member to extract.");
        return null;
    }

    @Override public boolean hasParameters() { return true; }
    @Override public AbstractRefactoring getRefactoring() { return refactoring; }
    @Override public HelpCtx getHelpCtx() { return null; }

    /** @return a conservative type name based on the original class. */
    private String defaultName() {
        return refactoring.getKind().name().equals("INTERFACE")
                ? "I" + discovery.getSourceName()
                : discovery.getSourceName() + "Base";
    }

    /** Swing panel containing the Extract Super fields and selectable IDEA member candidates. */
    private static final class ExtractSuperPanel implements CustomRefactoringPanel {
        private final JPanel component;
        private final JTextField nameField;
        private final JComboBox<KotlinPackageTargetRoot> rootCombo;
        private final JComboBox<String> packageCombo;
        private final JTextField targetFileField;
        private final JList<ExtractSuperMemberCandidate> memberList;
        private final JCheckBox abstractCheck;

        ExtractSuperPanel(List<ExtractSuperMemberCandidate> members, String defaultName,
                          KotlinPackageTarget target, ChangeListener changeListener) {
            nameField = new JTextField(defaultName, 28);
            rootCombo = new JComboBox<>(target.getRoots().toArray(new KotlinPackageTargetRoot[0]));
            rootCombo.setRenderer(new RootRenderer());
            selectDefaultRoot(target);
            packageCombo = new JComboBox<>();
            packageCombo.setEditable(true);
            updatePackages(target);
            targetFileField = new JTextField(defaultName + ".kt", 28);
            memberList = new JList<>(members.toArray(new ExtractSuperMemberCandidate[0]));
            if (!members.isEmpty()) {
                memberList.setSelectionInterval(0, members.size() - 1);
            }
            memberList.setCellRenderer(new CandidateRenderer());
            abstractCheck = new JCheckBox("Make selected members abstract", false);

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

            JLabel nameLabel = new JLabel("Extracted type name:");
            nameLabel.setLabelFor(nameField);
            form.add(nameLabel, label);
            form.add(nameField, field);
            JLabel rootLabel = new JLabel("Target source root:");
            rootLabel.setLabelFor(rootCombo);
            form.add(rootLabel, label);
            form.add(rootCombo, field);
            JLabel packageLabel = new JLabel("Target package:");
            packageLabel.setLabelFor(packageCombo);
            form.add(packageLabel, label);
            form.add(packageCombo, field);
            JLabel fileLabel = new JLabel("Target file:");
            fileLabel.setLabelFor(targetFileField);
            form.add(fileLabel, label);
            form.add(targetFileField, field);
            form.add(new JLabel("Members to extract:"), span);
            form.add(new JScrollPane(memberList), span);
            form.add(abstractCheck, span);

            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.CENTER);
            DocumentListener listener = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { changeListener.stateChanged(null); }
                @Override public void removeUpdate(DocumentEvent e) { changeListener.stateChanged(null); }
                @Override public void changedUpdate(DocumentEvent e) { changeListener.stateChanged(null); }
            };
            nameField.getDocument().addDocumentListener(listener);
            targetFileField.getDocument().addDocumentListener(listener);
            ((JTextField) packageCombo.getEditor().getEditorComponent())
                    .getDocument().addDocumentListener(listener);
            rootCombo.addActionListener(e -> {
                updatePackages(target);
                changeListener.stateChanged(null);
            });
            packageCombo.addActionListener(e -> changeListener.stateChanged(null));
            memberList.addListSelectionListener(e -> changeListener.stateChanged(null));
            abstractCheck.addActionListener(e -> changeListener.stateChanged(null));
        }

        /** @return requested extracted type name. */
        String name() { return nameField.getText(); }

        /** @return selected source-root path, or {@code null} when no root is available. */
        String targetRootPath() {
            KotlinPackageTargetRoot root = (KotlinPackageTargetRoot) rootCombo.getSelectedItem();
            return root == null ? null : root.getPath();
        }

        /** @return requested target package, empty for the default package. */
        String targetPackage() {
            Object item = packageCombo.getEditor().getItem();
            return item == null ? "" : item.toString();
        }

        /** @return requested target Kotlin filename. */
        String targetFile() { return targetFileField.getText(); }

        /** Selects the source-containing root when one is available. */
        private void selectDefaultRoot(KotlinPackageTarget target) {
            String defaultRoot = target.getDefaultRootPath();
            if (defaultRoot == null) return;
            for (int index = 0; index < rootCombo.getItemCount(); index++) {
                KotlinPackageTargetRoot root = rootCombo.getItemAt(index);
                if (defaultRoot.equals(root.getPath())) {
                    rootCombo.setSelectedIndex(index);
                    return;
                }
            }
        }

        /** Refreshes package suggestions after the source root changes. */
        private void updatePackages(KotlinPackageTarget target) {
            String selectedPackage = targetPackage();
            if (selectedPackage.isEmpty()) selectedPackage = target.getDefaultPackage();
            packageCombo.setModel(new DefaultComboBoxModel<>(
                    target.packages(targetRootPath()).toArray(new String[0])));
            packageCombo.setEditable(true);
            packageCombo.getEditor().setItem(selectedPackage);
        }

        /** @return source offsets of selected candidates. */
        Set<Integer> selectedOffsets() {
            Set<Integer> result = new LinkedHashSet<>();
            for (ExtractSuperMemberCandidate candidate : memberList.getSelectedValuesList()) {
                result.add(candidate.getOffset());
            }
            return result;
        }

        /** @return selected offsets when the common abstract flag is requested. */
        Set<Integer> abstractOffsets() {
            return abstractCheck.isSelected() ? selectedOffsets() : new LinkedHashSet<>();
        }

        @Override public void initialize() { nameField.requestFocusInWindow(); nameField.selectAll(); }
        @Override public Component getComponent() { return component; }
    }

    /** Displays a source root by its project-visible label. */
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

    /** Displays IDEA's own member presentation text in the list. */
    private static final class CandidateRenderer extends JLabel implements ListCellRenderer<ExtractSuperMemberCandidate> {
        @Override
        public Component getListCellRendererComponent(JList<? extends ExtractSuperMemberCandidate> list,
                                                      ExtractSuperMemberCandidate value, int index,
                                                      boolean selected, boolean focus) {
            setText(value.getPresentation());
            setOpaque(true);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
