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

import io.github.nbplugins.kotlin.refactoring.ConstantDestination;
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
 * {@link RefactoringUI} adapter for the Kotlin **Introduce Constant** refactoring.
 *
 * Shows a panel with:
 * <ul>
 *   <li>A name text field pre-filled with the first suggested name in SCREAMING_SNAKE_CASE.</li>
 *   <li>A "Replace all occurrences" checkbox (default: on).</li>
 *   <li>A Destination combo box (shown only when both companion-object and top-level are available).</li>
 * </ul>
 *
 * When the user clicks Preview or Refactor, {@link #setParameters()} copies all values into the
 * {@link KotlinIntroduceConstantRefactoring} carrier.
 *
 * @param suggestedNames   ordered list of name candidates; the first is shown as the default
 * @param destinations     available destinations (at least one)
 * @param refactoring      the carrier {@link KotlinIntroduceConstantRefactoring}
 */
public class KotlinIntroduceConstantUI implements RefactoringUI {

    private final KotlinIntroduceConstantRefactoring refactoring;
    private final List<String> suggestedNames;
    private final List<ConstantDestination> destinations;
    private ConstantPanel panel;

    /**
     * Creates an Introduce Constant UI.
     *
     * @param suggestedNames ordered name candidates; the first is shown as the default
     * @param destinations   available insertion destinations
     * @param refactoring    the carrier refactoring object
     */
    public KotlinIntroduceConstantUI(List<String> suggestedNames,
                                     List<ConstantDestination> destinations,
                                     KotlinIntroduceConstantRefactoring refactoring) {
        this.suggestedNames = suggestedNames;
        this.destinations = destinations;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Introduce Constant";
    }

    @Override
    public String getDescription() {
        return "Introduce compile-time constant";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            String defaultName = suggestedNames.isEmpty() ? "MY_CONST" : suggestedNames.get(0);
            panel = new ConstantPanel(defaultName, destinations, parent);
        }
        return panel;
    }

    /**
     * Copies the name-field value and all option flags into the refactoring carrier.
     *
     * @return {@code null} — validation is deferred to the plugin's {@code checkParameters()}
     */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setChosenName(panel.getNameValue());
            refactoring.setReplaceAll(panel.isReplaceAll());
            refactoring.setDestination(panel.getDestination());
        }
        return null;
    }

    @Override
    public Problem checkParameters() {
        if (panel != null) {
            String name = panel.getNameValue().trim();
            if (name.isEmpty()) {
                return new Problem(true, "Constant name must not be empty.");
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
     * Panel with name field, "Replace all occurrences" checkbox, and optional destination combo.
     * Mirrors IntelliJ IDEA's Introduce Constant dialog layout.
     */
    private static final class ConstantPanel implements CustomRefactoringPanel {

        private final JPanel component;
        private final JTextField nameField;
        private final JCheckBox replaceAllCheck;
        private final JComboBox<ConstantDestination> destinationCombo;

        ConstantPanel(String defaultName, List<ConstantDestination> destinations, ChangeListener changeListener) {
            nameField = new JTextField(defaultName, 30);
            nameField.selectAll();

            replaceAllCheck = new JCheckBox("Replace all occurrences", true);

            ConstantDestination[] destArray = destinations.toArray(new ConstantDestination[0]);
            destinationCombo = new JComboBox<>(destArray);
            // Default: companion object when available (first option from Computer).
            if (destArray.length > 0) destinationCombo.setSelectedIndex(0);

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

            form.add(replaceAllCheck, span);

            // Show destination combo only when there are multiple choices.
            if (destinations.size() > 1) {
                JLabel destLabel = new JLabel("Destination:");
                destLabel.setLabelFor(destinationCombo);
                form.add(destLabel, lc);
                form.add(destinationCombo, fc);
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

        /** Returns true if all occurrences should be replaced. */
        boolean isReplaceAll() { return replaceAllCheck.isSelected(); }

        /** Returns the selected insertion destination. */
        ConstantDestination getDestination() {
            ConstantDestination selected = (ConstantDestination) destinationCombo.getSelectedItem();
            return selected != null ? selected : ConstantDestination.TOP_LEVEL;
        }

        @Override public void initialize() { nameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }
}
