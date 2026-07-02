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
 * {@link RefactoringUI} adapter for the Kotlin **Copy Declaration** refactoring.
 *
 * Shows a panel with:
 * <ul>
 *   <li>A read-only "Declaration" label showing the name being copied.</li>
 *   <li>A "New file name" text field pre-filled with the suggested file name.</li>
 * </ul>
 *
 * When the user clicks Preview or Refactor, {@link #setParameters()} copies the file name into the
 * {@link KotlinCopyDeclarationRefactoring} carrier.
 *
 * @param initialResult  the analysis result from {@link io.github.nbplugins.kotlin.refactoring.KaCopyDeclarationComputer}
 * @param refactoring    the carrier {@link KotlinCopyDeclarationRefactoring}
 */
public class KotlinCopyDeclarationUI implements RefactoringUI {

    private final KotlinCopyDeclarationRefactoring refactoring;
    private final KaCopyDeclarationResult initialResult;
    private CopyPanel panel;

    /**
     * Creates a Copy Declaration UI.
     *
     * @param initialResult  analysis result containing the declaration name and suggested file name
     * @param refactoring    the carrier refactoring object
     */
    public KotlinCopyDeclarationUI(KaCopyDeclarationResult initialResult,
                                   KotlinCopyDeclarationRefactoring refactoring) {
        this.initialResult = initialResult;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Copy Declaration";
    }

    @Override
    public String getDescription() {
        return "Copy declaration '" + initialResult.getDeclarationName() + "' to a new file";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        if (panel == null) {
            panel = new CopyPanel(
                    initialResult.getDeclarationName(),
                    initialResult.getSuggestedFileName(),
                    parent
            );
        }
        return panel;
    }

    /**
     * Copies the target file name into the refactoring carrier.
     *
     * @return {@code null} — validation is deferred to the plugin's {@code checkParameters()}
     */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            refactoring.setTargetFileName(panel.getFileNameValue());
        }
        return null;
    }

    @Override
    public Problem checkParameters() {
        if (panel != null) {
            String name = panel.getFileNameValue().trim();
            if (name.isEmpty()) {
                return new Problem(true, "Target file name must not be empty.");
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
     * Panel with declaration info label and new file name field.
     */
    private static final class CopyPanel implements CustomRefactoringPanel {

        private final JPanel component;
        private final JTextField fileNameField;

        CopyPanel(String declarationName, String suggestedFileName, ChangeListener changeListener) {
            fileNameField = new JTextField(suggestedFileName, 30);
            fileNameField.selectAll();

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

            JLabel declLabel = new JLabel("Declaration:");
            JLabel declValue = new JLabel(declarationName);
            form.add(declLabel, lc);
            form.add(declValue, fc);

            JLabel fileLabel = new JLabel("New file name:");
            fileLabel.setLabelFor(fileNameField);
            form.add(fileLabel, lc);
            form.add(fileNameField, fc);

            component = new JPanel(new BorderLayout());
            component.add(form, BorderLayout.NORTH);

            fileNameField.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e)  { changeListener.stateChanged(null); }
                @Override public void removeUpdate(DocumentEvent e)  { changeListener.stateChanged(null); }
                @Override public void changedUpdate(DocumentEvent e) { changeListener.stateChanged(null); }
            });
        }

        /** Returns the current text in the file name field. */
        String getFileNameValue() { return fileNameField.getText(); }

        @Override public void initialize() { fileNameField.requestFocusInWindow(); }
        @Override public Component getComponent() { return component; }
    }
}
