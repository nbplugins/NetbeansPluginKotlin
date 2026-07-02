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

import io.github.nbplugins.kotlin.refactoring.KaIntroducePropertyResult;
import io.github.nbplugins.kotlin.refactoring.ScopeCandidate;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
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
 * {@link RefactoringUI} adapter for the Kotlin **Introduce Property** refactoring.
 *
 * Shows a panel with:
 * <ul>
 *   <li>A name text field pre-filled with the first suggested name.</li>
 *   <li>{@code val} / {@code var} radio buttons (default: {@code val}).</li>
 *   <li>An optional "Destination" combo box (shown when multiple scopes are available).</li>
 * </ul>
 *
 * When the user clicks Preview or Refactor, {@link #setParameters()} copies all values into the
 * {@link KotlinIntroducePropertyRefactoring} carrier.
 *
 * @param initialResult   the analysis result from {@link io.github.nbplugins.kotlin.refactoring.KaIntroducePropertyComputer}
 * @param scopeCandidates valid property introduction scopes (class body and file level)
 * @param refactoring     the carrier {@link KotlinIntroducePropertyRefactoring}
 */
public class KotlinIntroducePropertyUI implements RefactoringUI {

    private final KotlinIntroducePropertyRefactoring refactoring;
    private final KaIntroducePropertyResult initialResult;
    private final List<ScopeCandidate> scopeCandidates;
    private PropertyPanel panel;

    /**
     * Creates an Introduce Property UI.
     *
     * @param initialResult   analysis result containing suggested names
     * @param scopeCandidates available introduction scopes
     * @param refactoring     the carrier refactoring object
     */
    public KotlinIntroducePropertyUI(KaIntroducePropertyResult initialResult,
                                     List<ScopeCandidate> scopeCandidates,
                                     KotlinIntroducePropertyRefactoring refactoring) {
        this.initialResult = initialResult;
        this.scopeCandidates = scopeCandidates;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Introduce Property";
    }

    @Override
    public String getDescription() {
        return "Introduce expression as a class or file-level property";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            List<String> names = initialResult.getSuggestedNames();
            String defaultName = names.isEmpty() ? "myProperty" : names.get(0);
            panel = new PropertyPanel(defaultName, scopeCandidates, parent);
        }
        return panel;
    }

    /**
     * Copies the name-field value, {@code val}/{@code var} selection, and chosen scope into the
     * refactoring carrier.
     *
     * @return {@code null} — validation is deferred to the plugin's {@code checkParameters()}
     */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setChosenName(panel.getNameValue());
            refactoring.setUseVar(panel.isUseVar());
            ScopeCandidate chosen = panel.getChosenScope();
            if (chosen != null) {
                refactoring.setTargetSiblingOffset(chosen.getTargetSiblingOffset());
            }
        }
        return null;
    }

    @Override
    public Problem checkParameters() {
        if (panel != null) {
            String name = panel.getNameValue().trim();
            if (name.isEmpty()) {
                return new Problem(true, "Property name must not be empty.");
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
     * Panel with name field, val/var radio buttons, and optional destination combo.
     * Mirrors IntelliJ IDEA's Introduce Property dialog layout.
     */
    private static final class PropertyPanel implements CustomRefactoringPanel {

        private final JPanel component;
        private final JTextField nameField;
        private final JRadioButton valButton;
        private final JRadioButton varButton;
        private final JComboBox<ScopeCandidate> scopeCombo;

        PropertyPanel(String defaultName, List<ScopeCandidate> scopeCandidates, ChangeListener changeListener) {
            nameField = new JTextField(defaultName, 30);
            nameField.selectAll();

            valButton = new JRadioButton("val", true);
            varButton = new JRadioButton("var", false);
            ButtonGroup group = new ButtonGroup();
            group.add(valButton);
            group.add(varButton);

            ScopeCandidate[] scopeArray = scopeCandidates.toArray(new ScopeCandidate[0]);
            scopeCombo = new JComboBox<>(scopeArray);

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

            JLabel nameLabel = new JLabel("Name:");
            nameLabel.setLabelFor(nameField);
            form.add(nameLabel, lc);
            form.add(nameField, fc);

            JPanel radioPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            radioPanel.add(valButton);
            radioPanel.add(varButton);
            form.add(radioPanel, span);

            // Show scope combo only when there are multiple scope choices.
            if (scopeCandidates.size() > 1) {
                JLabel destLabel = new JLabel("Destination:");
                destLabel.setLabelFor(scopeCombo);
                form.add(destLabel, lc);
                form.add(scopeCombo, fc);
            }

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

        /** Returns {@code true} when the user selected {@code var}. */
        boolean isUseVar() { return varButton.isSelected(); }

        /** Returns the scope chosen in the combo box, or {@code null} when no combo is shown. */
        ScopeCandidate getChosenScope() {
            return (ScopeCandidate) scopeCombo.getSelectedItem();
        }

        @Override public void initialize() { nameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }
}
