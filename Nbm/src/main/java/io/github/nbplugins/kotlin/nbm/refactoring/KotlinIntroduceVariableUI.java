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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
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
 * {@link RefactoringUI} adapter for the Kotlin **Introduce Variable** refactoring.
 *
 * Shows a panel with a name text field pre-filled with the first suggested name.  When the user
 * clicks Preview or Refactor, {@link #setParameters()} copies the field's value into
 * {@link KotlinIntroduceVariableRefactoring#setChosenName(String)}.
 *
 * @param suggestedNames ordered list of name candidates (first is the default); may be empty
 * @param refactoring    the carrier {@link KotlinIntroduceVariableRefactoring}
 */
public class KotlinIntroduceVariableUI implements RefactoringUI {

    private final KotlinIntroduceVariableRefactoring refactoring;
    private final List<String> suggestedNames;
    private NamePanel panel;

    /**
     * Creates an Introduce Variable UI.
     *
     * @param suggestedNames ordered name candidates; the first is shown as the default
     * @param refactoring    the carrier refactoring object
     */
    public KotlinIntroduceVariableUI(List<String> suggestedNames,
                                     KotlinIntroduceVariableRefactoring refactoring) {
        this.suggestedNames = suggestedNames;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Introduce Variable";
    }

    @Override
    public String getDescription() {
        return "Introduce local variable";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            String defaultName = suggestedNames.isEmpty() ? "value" : suggestedNames.get(0);
            panel = new NamePanel(defaultName, parent);
        }
        return panel;
    }

    /**
     * Copies the name-field value and all option flags into the refactoring carrier.
     *
     * @return {@code null} — no problems to report; validation is deferred to the plugin's
     *         {@code checkParameters()}
     */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setChosenName(panel.getNameValue());
            refactoring.setReplaceAll(panel.isReplaceAll());
            refactoring.setUseVar(panel.isUseVar());
            refactoring.setExplicitType(panel.isExplicitType());
        }
        return null;
    }

    @Override
    public Problem checkParameters() {
        if (panel != null) {
            String name = panel.getNameValue().trim();
            if (name.isEmpty()) {
                return new Problem(true, "Variable name must not be empty.");
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
     * Panel with a name field and three option controls matching IDEA's Introduce Variable dialog:
     * "Replace all occurrences" checkbox, val/var radio buttons, and "Specify type explicitly" checkbox.
     */
    private static final class NamePanel implements CustomRefactoringPanel {

        private final JPanel component;
        private final JTextField nameField;
        private final JCheckBox replaceAllCheck;
        private final JRadioButton valRadio;
        private final JRadioButton varRadio;
        private final JCheckBox explicitTypeCheck;

        NamePanel(String defaultName, ChangeListener changeListener) {
            nameField = new JTextField(defaultName, 30);
            nameField.selectAll();

            replaceAllCheck = new JCheckBox("Replace all occurrences", true);
            valRadio = new JRadioButton("val", true);
            varRadio = new JRadioButton("var", false);
            explicitTypeCheck = new JCheckBox("Specify type explicitly", false);

            ButtonGroup varValGroup = new ButtonGroup();
            varValGroup.add(valRadio);
            varValGroup.add(varRadio);

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

            JPanel varValPanel = new JPanel(new GridBagLayout());
            GridBagConstraints rbc = new GridBagConstraints();
            rbc.anchor = GridBagConstraints.WEST;
            rbc.insets = new Insets(0, 0, 0, 8);
            varValPanel.add(valRadio, rbc);
            rbc.insets = new Insets(0, 0, 0, 0);
            varValPanel.add(varRadio, rbc);
            GridBagConstraints varValSpan = new GridBagConstraints();
            varValSpan.gridwidth = GridBagConstraints.REMAINDER;
            varValSpan.anchor = GridBagConstraints.WEST;
            varValSpan.insets = new Insets(0, 20, 2, 0);
            form.add(varValPanel, varValSpan);

            form.add(explicitTypeCheck, span);

            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.NORTH);

            // Notify the framework when the name changes so Preview/Refactor buttons update.
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

        /** Returns true if a {@code var} declaration should be generated (false → {@code val}). */
        boolean isUseVar() { return varRadio.isSelected(); }

        /** Returns true if the type annotation should be written explicitly. */
        boolean isExplicitType() { return explicitTypeCheck.isSelected(); }

        @Override public void initialize() { nameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }
}
