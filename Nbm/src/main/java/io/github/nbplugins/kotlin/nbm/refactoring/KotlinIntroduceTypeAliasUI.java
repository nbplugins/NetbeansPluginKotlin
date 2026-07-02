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

import io.github.nbplugins.kotlin.refactoring.KaIntroduceTypeAliasResult;
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
 * {@link RefactoringUI} adapter for the Kotlin **Introduce Type Alias** refactoring.
 *
 * Shows a panel with:
 * <ul>
 *   <li>A name text field pre-filled with the suggested alias name.</li>
 *   <li>A "Replace all occurrences" checkbox (default: on).</li>
 *   <li>A visibility combo box ({@code public}, {@code internal}, {@code private}).</li>
 * </ul>
 *
 * When the user clicks Preview or Refactor, {@link #setParameters()} copies all values into the
 * {@link KotlinIntroduceTypeAliasRefactoring} carrier.
 *
 * @param initialResult  the analysis result from {@link io.github.nbplugins.kotlin.refactoring.KaIntroduceTypeAliasComputer}
 * @param refactoring    the carrier {@link KotlinIntroduceTypeAliasRefactoring}
 */
public class KotlinIntroduceTypeAliasUI implements RefactoringUI {

    private final KotlinIntroduceTypeAliasRefactoring refactoring;
    private final KaIntroduceTypeAliasResult initialResult;
    private AliasPanel panel;

    /**
     * Creates an Introduce Type Alias UI.
     *
     * @param initialResult  analysis result containing the suggested name and occurrence count
     * @param refactoring    the carrier refactoring object
     */
    public KotlinIntroduceTypeAliasUI(KaIntroduceTypeAliasResult initialResult,
                                      KotlinIntroduceTypeAliasRefactoring refactoring) {
        this.initialResult = initialResult;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Introduce Type Alias";
    }

    @Override
    public String getDescription() {
        return "Introduce a typealias for the selected type reference";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            panel = new AliasPanel(
                    initialResult.getSuggestedName(),
                    initialResult.getAvailableVisibilities(),
                    parent
            );
        }
        return panel;
    }

    /**
     * Copies the name-field value, replace-all flag, and visibility into the refactoring carrier.
     *
     * @return {@code null} — validation is deferred to the plugin's {@code checkParameters()}
     */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setChosenName(panel.getNameValue());
            refactoring.setReplaceAll(panel.isReplaceAll());
            refactoring.setVisibility(panel.getVisibility());
        }
        return null;
    }

    @Override
    public Problem checkParameters() {
        if (panel != null) {
            String name = panel.getNameValue().trim();
            if (name.isEmpty()) {
                return new Problem(true, "Type alias name must not be empty.");
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
     * Panel with alias name field, replace-all checkbox, and visibility combo.
     * Mirrors IntelliJ IDEA's Introduce Type Alias dialog layout.
     */
    private static final class AliasPanel implements CustomRefactoringPanel {

        private final JPanel component;
        private final JTextField nameField;
        private final JCheckBox replaceAllCheck;
        private final JComboBox<String> visibilityCombo;

        AliasPanel(String defaultName, List<String> visibilities, ChangeListener changeListener) {
            nameField = new JTextField(defaultName, 30);
            nameField.selectAll();

            replaceAllCheck = new JCheckBox("Replace all occurrences", true);

            String[] visArray = visibilities.toArray(new String[0]);
            visibilityCombo = new JComboBox<>(visArray.length > 0 ? visArray : new String[]{"public"});

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

            GridBagConstraints span = new GridBagConstraints();
            span.gridwidth = GridBagConstraints.REMAINDER;
            span.anchor = GridBagConstraints.WEST;
            span.insets = new Insets(2, 0, 2, 0);

            JLabel nameLabel = new JLabel("Alias name:");
            nameLabel.setLabelFor(nameField);
            form.add(nameLabel, lc);
            form.add(nameField, fc);

            form.add(replaceAllCheck, span);

            JLabel visLabel = new JLabel("Visibility:");
            visLabel.setLabelFor(visibilityCombo);
            form.add(visLabel, lc);
            form.add(visibilityCombo, fc);

            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.NORTH);

            nameField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e)  { changeListener.stateChanged(null); }
                @Override public void removeUpdate(DocumentEvent e)  { changeListener.stateChanged(null); }
                @Override public void changedUpdate(DocumentEvent e) { changeListener.stateChanged(null); }
            });
        }

        /** Returns the current text in the name field. */
        String getNameValue() { return nameField.getText(); }

        /** Returns {@code true} when all occurrences should be replaced. */
        boolean isReplaceAll() { return replaceAllCheck.isSelected(); }

        /** Returns the selected visibility keyword (e.g. {@code "public"}, {@code "internal"}). */
        String getVisibility() {
            Object selected = visibilityCombo.getSelectedItem();
            return selected != null ? selected.toString() : "public";
        }

        @Override public void initialize() { nameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }
}
