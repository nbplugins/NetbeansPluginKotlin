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

import io.github.nbplugins.kotlin.refactoring.KaMoveFileResult;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
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
 * Configures Kotlin Move File's source-root/package destination and supported reference updates.
 *
 * @param initialResult discovered source-file data
 * @param refactoring mutable carrier that receives selected parameters
 * @param target available NetBeans source roots and package validator
 */
public class KotlinMoveFileUI implements RefactoringUI {

    private final KaMoveFileResult initialResult;
    private final KotlinMoveFileRefactoring refactoring;
    private final KotlinPackageTarget target;
    private final String initialPackage;
    private MoveFilePanel panel;

    /**
     * Creates the Move File UI adapter.
     *
     * @param initialResult discovered source-file data
     * @param refactoring mutable carrier that receives selected parameters
     * @param target available NetBeans source roots and package validator
     */
    public KotlinMoveFileUI(KaMoveFileResult initialResult,
                            KotlinMoveFileRefactoring refactoring,
                            KotlinPackageTarget target) {
        this(initialResult, refactoring, target, initialResult.getPackageName());
    }

    /**
     * Creates a Move File or Move Directory UI adapter with an explicit initial package.
     *
     * @param initialResult discovered representative Kotlin-file data
     * @param refactoring mutable carrier that receives selected parameters
     * @param target available NetBeans source roots and package validator
     * @param initialPackage package initially shown in the target field
     */
    public KotlinMoveFileUI(KaMoveFileResult initialResult,
                            KotlinMoveFileRefactoring refactoring,
                            KotlinPackageTarget target,
                            String initialPackage) {
        this.initialResult = initialResult;
        this.refactoring = refactoring;
        this.target = target;
        this.initialPackage = initialPackage;
    }

    /** @return the user-visible refactoring name. */
    @Override
    public String getName() {
        return refactoring.getSelection().isDirectory() ? "Move Kotlin Directory" : "Move Kotlin File";
    }

    /** @return concise user-visible description of the selected file or directory. */
    @Override
    public String getDescription() {
        if (refactoring.getSelection().isDirectory()) {
            return "Move " + refactoring.getSelection().getSourceFiles().size() + " Kotlin files from directory '"
                    + refactoring.getSelection().getDisplayName() + "' to another package";
        }
        return "Move Kotlin file '" + initialResult.getFileName() + "' to another package";
    }

    /** @return false because Move File mutates project sources. */
    @Override
    public boolean isQuery() {
        return false;
    }

    /**
     * Creates the destination form on first use.
     *
     * @param parent listener notified when form values change
     * @return the cached Move File form
     */
    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            panel = new MoveFilePanel(
                    refactoring.getSelection().isDirectory()
                            ? refactoring.getSelection().getDisplayName() + " ("
                                    + refactoring.getSelection().getSourceFiles().size() + " Kotlin files)"
                            : initialResult.getFileName(),
                    initialPackage,
                    initialResult.getPackageMayBeUpdated(),
                    refactoring.getSelection().isDirectory(),
                    target.getRoots(),
                    target.getDefaultRootPath(),
                    parent
            );
        }
        return panel;
    }

    /**
     * Copies selected UI values to the refactoring carrier.
     *
     * @return a fatal problem for an invalid target package, otherwise {@code null}
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
        refactoring.setTargetRootPath(panel.getRootPath());
        refactoring.setTargetPackage(packageName);
        refactoring.setUpdateReferences(panel.isUpdateReferences());
        return null;
    }

    /**
     * Validates current form values before NetBeans schedules the refactoring.
     *
     * @return a fatal problem when the destination is invalid, otherwise {@code null}
     */
    @Override
    public Problem checkParameters() {
        return setParameters();
    }

    /** @return true because Move File has a destination configuration form. */
    @Override
    public boolean hasParameters() {
        return true;
    }

    /** @return the refactoring carrier used by NetBeans. */
    @Override
    public AbstractRefactoring getRefactoring() {
        return refactoring;
    }

    /** @return no dedicated help page. */
    @Override
    public HelpCtx getHelpCtx() {
        return null;
    }

    /** Swing form for Move File parameters. */
    private static final class MoveFilePanel implements CustomRefactoringPanel {
        private final JPanel component;
        private final JComboBox<KotlinPackageTargetRoot> rootField;
        private final JTextField packageField;
        private final JCheckBox updateReferences;

        /**
         * Builds the destination form.
         *
         * @param fileName displayed source file name
         * @param sourcePackage initially selected package
         * @param mayUpdatePackage whether automatic package rewriting is available
         * @param directorySelection whether the selected directory name is retained below the target
         *                           package
         * @param roots selectable destination source roots
         * @param defaultRoot source root containing the current file, if available
         * @param changeListener listener notified when values change
         */
        MoveFilePanel(String fileName,
                      String sourcePackage,
                      boolean mayUpdatePackage,
                      boolean directorySelection,
                      List<KotlinPackageTargetRoot> roots,
                      String defaultRoot,
                      ChangeListener changeListener) {
            rootField = new JComboBox<>(roots.toArray(new KotlinPackageTargetRoot[0]));
            rootField.setRenderer(new RootRenderer());
            for (int index = 0; index < rootField.getItemCount(); index++) {
                KotlinPackageTargetRoot root = rootField.getItemAt(index);
                if (root.getPath().equals(defaultRoot)) {
                    rootField.setSelectedIndex(index);
                    break;
                }
            }
            packageField = new JTextField(sourcePackage, 30);
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
            form.add(new JLabel(fileName), field);
            JLabel rootLabel = new JLabel("Target source root:");
            rootLabel.setLabelFor(rootField);
            form.add(rootLabel, label);
            form.add(rootField, field);
            JLabel packageLabel = new JLabel("Target package:");
            packageLabel.setLabelFor(packageField);
            form.add(packageLabel, label);
            form.add(packageField, field);
            form.add(new JLabel(""), label);
            form.add(updateReferences, field);
            if (directorySelection) {
                form.add(new JLabel(""), label);
                form.add(new JLabel("The selected directory name and nested folders are retained below the target package."), field);
            }
            if (!mayUpdatePackage) {
                form.add(new JLabel(""), label);
                form.add(new JLabel("Package differs from its current directory and will be preserved."), field);
            }

            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.NORTH);
            DocumentListener listener = new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
                @Override public void removeUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
                @Override public void changedUpdate(DocumentEvent event) { changeListener.stateChanged(null); }
            };
            packageField.getDocument().addDocumentListener(listener);
            rootField.addActionListener(event -> changeListener.stateChanged(null));
            updateReferences.addActionListener(event -> changeListener.stateChanged(null));
        }

        /** @return selected source-root path or an empty string. */
        String getRootPath() {
            KotlinPackageTargetRoot root = (KotlinPackageTargetRoot) rootField.getSelectedItem();
            return root == null ? "" : root.getPath();
        }

        /** @return current target package text. */
        String getPackageValue() {
            return packageField.getText();
        }

        /** @return whether supported Kotlin references should be retargeted. */
        boolean isUpdateReferences() {
            return updateReferences.isSelected();
        }

        /** Requests initial focus for the target package field. */
        @Override
        public void initialize() {
            packageField.requestFocusInWindow();
        }

        /** @return Swing component displayed by NetBeans. */
        @Override
        public Component getComponent() {
            return component;
        }
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
