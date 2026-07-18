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

import io.github.nbplugins.kotlin.refactoring.KaIntroduceParameterRequest;
import io.github.nbplugins.kotlin.refactoring.KaIntroduceParameterResult;
import io.github.nbplugins.kotlin.refactoring.ScopeCandidate;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
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
 * {@link RefactoringUI} adapter for the Kotlin **Introduce Parameter** refactoring (E9.13).
 *
 * Shows a panel with:
 * <ul>
 *   <li>A name text field pre-filled with the first suggested name.</li>
 *   <li>An editable type combo box, pre-filled with the inferred type and its supertypes.</li>
 *   <li>A "Replace all N occurrences" checkbox, shown only when more than one occurrence exists.</li>
 *   <li>A "Use default value" checkbox.</li>
 *   <li>An optional "Target" combo box, shown when multiple enclosing scopes are available.</li>
 * </ul>
 *
 * When the user clicks Preview or Refactor, {@link #setParameters()} copies all values into the
 * {@link KotlinIntroduceParameterRefactoring} carrier as a {@link KaIntroduceParameterRequest}.
 *
 * @param initialResult   the analysis result from {@link KaIntroduceParameterComputer}
 * @param scopeCandidates valid enclosing declarations the parameter can be introduced into
 * @param refactoring     the carrier {@link KotlinIntroduceParameterRefactoring}
 */
public class KotlinIntroduceParameterUI implements RefactoringUI {

    private final KotlinIntroduceParameterRefactoring refactoring;
    private final KaIntroduceParameterResult initialResult;
    private final List<ScopeCandidate> scopeCandidates;
    private ParameterPanel panel;

    /**
     * Creates an Introduce Parameter UI.
     *
     * @param initialResult   analysis result containing suggested names and the inferred type
     * @param scopeCandidates available enclosing declarations
     * @param refactoring     the carrier refactoring object
     */
    public KotlinIntroduceParameterUI(KaIntroduceParameterResult initialResult,
                                       List<ScopeCandidate> scopeCandidates,
                                       KotlinIntroduceParameterRefactoring refactoring) {
        this.initialResult = initialResult;
        this.scopeCandidates = scopeCandidates;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Introduce Parameter";
    }

    @Override
    public String getDescription() {
        return "Introduce expression as a new parameter, updating all call sites";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            List<String> names = initialResult.getSuggestedNames();
            String defaultName = names.isEmpty() ? "p0" : names.get(0);
            panel = new ParameterPanel(defaultName, initialResult, scopeCandidates, parent);
        }
        return panel;
    }

    /**
     * Copies the name/type-field values and checkbox/scope selections into the refactoring carrier
     * as a {@link KaIntroduceParameterRequest}.
     *
     * @return {@code null} — validation is deferred to {@link #checkParameters()}
     */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            ScopeCandidate chosen = panel.getChosenScope();
            refactoring.setRequest(new KaIntroduceParameterRequest(
                    panel.getNameValue(),
                    panel.getTypeValue(),
                    panel.isReplaceAllOccurrences(),
                    panel.isUseDefaultValue(),
                    chosen != null ? chosen.getTargetSiblingOffset() : null
            ));
        }
        return null;
    }

    @Override
    public Problem checkParameters() {
        if (panel != null) {
            String name = panel.getNameValue().trim();
            if (name.isEmpty()) {
                return new Problem(true, "Parameter name must not be empty.");
            }
            String type = panel.getTypeValue().trim();
            if (type.isEmpty()) {
                return new Problem(true, "Parameter type must not be empty.");
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
     * Panel with name field, editable type combo, replace-occurrences/default-value checkboxes, and
     * an optional target-scope combo. Mirrors IntelliJ IDEA's Introduce Parameter dialog layout.
     */
    private static final class ParameterPanel implements CustomRefactoringPanel {

        private final JPanel component;
        private final JTextField nameField;
        private final JComboBox<String> typeCombo;
        private final JCheckBox replaceAllCheckBox;
        private final JCheckBox useDefaultValueCheckBox;
        private final JComboBox<ScopeCandidate> scopeCombo;

        ParameterPanel(String defaultName, KaIntroduceParameterResult initialResult,
                       List<ScopeCandidate> scopeCandidates, ChangeListener changeListener) {
            nameField = new JTextField(defaultName, 30);
            nameField.selectAll();

            List<String> types = new ArrayList<>();
            types.add(initialResult.getTypeText());
            types.addAll(initialResult.getSupertypeTexts());
            typeCombo = new JComboBox<>(types.toArray(new String[0]));
            typeCombo.setEditable(true);

            int occurrenceCount = initialResult.getOccurrenceCount();
            replaceAllCheckBox = new JCheckBox("Replace all " + occurrenceCount + " occurrences", occurrenceCount > 1);
            useDefaultValueCheckBox = new JCheckBox("Use default value", false);

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

            JLabel typeLabel = new JLabel("Type:");
            typeLabel.setLabelFor(typeCombo);
            form.add(typeLabel, lc);
            form.add(typeCombo, fc);

            if (occurrenceCount > 1) {
                form.add(replaceAllCheckBox, span);
            }
            form.add(useDefaultValueCheckBox, span);

            if (scopeCandidates.size() > 1) {
                JLabel destLabel = new JLabel("Target:");
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

        /** Returns the current text in the (editable) type combo box. */
        String getTypeValue() {
            Object selected = typeCombo.getEditor().getItem();
            return selected == null ? "" : selected.toString();
        }

        /** Returns {@code true} when "replace all occurrences" is checked. */
        boolean isReplaceAllOccurrences() { return replaceAllCheckBox.isSelected(); }

        /** Returns {@code true} when "use default value" is checked. */
        boolean isUseDefaultValue() { return useDefaultValueCheckBox.isSelected(); }

        /** Returns the scope chosen in the combo box, or {@code null} when no combo is shown. */
        ScopeCandidate getChosenScope() {
            return (ScopeCandidate) scopeCombo.getSelectedItem();
        }

        @Override public void initialize() { nameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }
}
