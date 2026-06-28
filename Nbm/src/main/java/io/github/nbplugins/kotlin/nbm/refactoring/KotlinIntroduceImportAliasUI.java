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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * NetBeans dialog panel for the Kotlin <strong>Introduce Import Alias</strong> refactoring.
 *
 * <p>Shows a single text field pre-filled with the current short name of the import.
 * The user edits it to choose the desired alias before clicking <em>Refactor</em>.
 *
 * <p>Mirrors the minimal interaction of IDEA's {@code KotlinIntroduceImportAliasHandler},
 * which uses in-place rename; here a modal panel is used because NetBeans' refactoring
 * framework is dialog-based.
 */
public class KotlinIntroduceImportAliasUI implements RefactoringUI {

    private final KotlinIntroduceImportAliasRefactoring refactoring;
    /** Short name of the imported symbol, pre-filled as the default alias suggestion. */
    private final String defaultAlias;
    private Panel panel;

    /**
     * Creates the UI for the given refactoring.
     *
     * @param refactoring  the carrier refactoring object
     * @param shortName    the current short name of the import (pre-filled in the Alias field)
     */
    public KotlinIntroduceImportAliasUI(KotlinIntroduceImportAliasRefactoring refactoring, String shortName) {
        this.refactoring = refactoring;
        this.defaultAlias = shortName;
    }

    @Override
    public String getName() {
        return "Introduce Import Alias";
    }

    @Override
    public String getDescription() {
        return "Add an alias to the import directive and rename all usages in this file";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener changeListener) {
        if (panel == null) {
            panel = new Panel(defaultAlias);
        }
        return panel;
    }

    /** Reads the alias name from the panel and stores it in the refactoring carrier. */
    @Override
    public Problem setParameters() {
        if (panel != null) {
            String alias = panel.getAliasName().trim();
            if (!alias.isEmpty()) {
                refactoring.setChosenAlias(alias);
            }
        }
        return null;
    }

    @Override
    public Problem checkParameters() {
        if (panel != null && panel.getAliasName().trim().isEmpty()) {
            return new Problem(true, "Alias name must not be empty.");
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
        return HelpCtx.DEFAULT_HELP;
    }

    /** The panel shown inside the NetBeans refactoring dialog. */
    static class Panel extends JPanel implements CustomRefactoringPanel {

        private final JTextField aliasField;

        /**
         * Constructs the panel with [defaultAlias] pre-filled in the Alias field.
         *
         * @param defaultAlias  the short name of the import, used as the initial alias value
         */
        Panel(String defaultAlias) {
            super(new BorderLayout());
            setBorder(new EmptyBorder(8, 8, 8, 8));

            JPanel inner = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.anchor = GridBagConstraints.WEST;

            gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;
            inner.add(new JLabel("Alias name:"), gbc);

            aliasField = new JTextField(defaultAlias, 30);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            inner.add(aliasField, gbc);

            add(inner, BorderLayout.NORTH);
        }

        /** Returns the alias name currently entered by the user. */
        String getAliasName() {
            return aliasField.getText();
        }

        @Override
        public void initialize() {
            SwingUtilities.invokeLater(() -> {
                aliasField.selectAll();
                aliasField.requestFocusInWindow();
            });
        }

        @Override
        public Component getComponent() {
            return this;
        }
    }
}
