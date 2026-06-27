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

import io.github.nbplugins.kotlin.refactoring.ScopeCandidate;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
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
 * {@link RefactoringUI} adapter for the Kotlin **Extract Function** refactoring.
 *
 * Shows a panel with a function-name text field pre-filled with the first suggested name.
 * When the user clicks Preview or Refactor, {@link #setParameters()} copies the field's value
 * into {@link KotlinExtractFunctionRefactoring#setChosenName(String)}.
 *
 * @param suggestedNames ordered list of name candidates (first is the default); may be empty
 * @param refactoring    the carrier {@link KotlinExtractFunctionRefactoring}
 */
public class KotlinExtractFunctionUI implements RefactoringUI {

    private final KotlinExtractFunctionRefactoring refactoring;
    private final List<String> suggestedNames;
    private final List<ScopeCandidate> scopeCandidates;
    private NamePanel panel;

    /**
     * Creates an Extract Function UI.
     *
     * @param suggestedNames   ordered name candidates; the first is shown as the default
     * @param scopeCandidates  valid extraction scopes; when more than one is present a
     *                         "Destination" combo box is shown (innermost scope is index 0)
     * @param refactoring      the carrier refactoring object
     */
    public KotlinExtractFunctionUI(List<String> suggestedNames,
                                   List<ScopeCandidate> scopeCandidates,
                                   KotlinExtractFunctionRefactoring refactoring) {
        this.suggestedNames = suggestedNames;
        this.scopeCandidates = scopeCandidates;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Extract Function";
    }

    @Override
    public String getDescription() {
        return "Extract selected code into a new function";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            String defaultName = suggestedNames.isEmpty() ? "extractedFunction" : suggestedNames.get(0);
            panel = new NamePanel(defaultName, scopeCandidates, parent);
        }
        return panel;
    }

    /**
     * Copies the current name-field value and chosen scope index into the refactoring carrier.
     *
     * @return {@code null} — no problems to report; validation is deferred to the plugin's
     *         {@code checkParameters()}
     */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setChosenName(panel.getNameValue());
            refactoring.setChosenScopeIndex(panel.getScopeIndex());
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
     * Panel with a function-name field and an optional "Destination" combo box.
     *
     * The combo box is shown only when there are two or more valid extraction scopes so that
     * single-scope extractions stay as simple as before.
     */
    private static final class NamePanel implements CustomRefactoringPanel {

        private final JPanel component;
        private final JTextField nameField;
        private final JComboBox<String> scopeCombo;

        NamePanel(String defaultName, List<ScopeCandidate> scopeCandidates,
                  ChangeListener changeListener) {
            nameField = new JTextField(defaultName, 30);
            nameField.selectAll();

            // Build the scope combo unconditionally but show it only when there are multiple scopes.
            String[] labels = new String[scopeCandidates.size()];
            for (int i = 0; i < scopeCandidates.size(); i++) {
                labels[i] = scopeCandidates.get(i).getLabel();
            }
            scopeCombo = new JComboBox<>(labels);

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

            JLabel nameLabel = new JLabel("Function name:");
            nameLabel.setLabelFor(nameField);
            form.add(nameLabel, lc);
            form.add(nameField, fc);

            if (scopeCandidates.size() > 1) {
                JLabel scopeLabel = new JLabel("Destination:");
                scopeLabel.setLabelFor(scopeCombo);
                form.add(scopeLabel, lc);
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
        String getNameValue() {
            return nameField.getText();
        }

        /** Returns the selected scope index (0 = innermost). */
        int getScopeIndex() {
            int idx = scopeCombo.getSelectedIndex();
            return idx < 0 ? 0 : idx;
        }

        @Override public void initialize() { nameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }
}
