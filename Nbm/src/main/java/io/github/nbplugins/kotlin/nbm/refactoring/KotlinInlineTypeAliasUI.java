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
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.event.ChangeListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * NetBeans UI adapter for Kotlin **Inline Type Alias**.
 *
 * Users can expand every portable Kotlin usage or only the invocation usage. Expanding all usages
 * optionally retains the declaration, mirroring the IDEA type-alias dialog without importing its
 * IntelliJ UI and command lifecycle.
 *
 * @param symbolName display name of the selected type alias
 * @param refactoring carrier receiving the selected options
 */
public final class KotlinInlineTypeAliasUI implements RefactoringUI {

    private final String symbolName;
    private final KotlinInlineTypeAliasRefactoring refactoring;

    /**
     * Creates a type-alias inline UI.
     *
     * @param symbolName selected alias name, possibly empty before validation
     * @param refactoring carrier receiving options
     */
    public KotlinInlineTypeAliasUI(String symbolName, KotlinInlineTypeAliasRefactoring refactoring) {
        this.symbolName = symbolName;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Inline Type Alias";
    }

    @Override
    public String getDescription() {
        return symbolName == null || symbolName.isEmpty()
                ? getName()
                : "Inline type alias '" + symbolName + "'";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        return new InlineTypeAliasPanel(refactoring, getDescription());
    }

    @Override
    public Problem setParameters() {
        return null;
    }

    @Override
    public Problem checkParameters() {
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

    /** Panel binding the two supported type-alias inline choices to the carrier. */
    private static final class InlineTypeAliasPanel implements CustomRefactoringPanel {
        private final JPanel component = new JPanel(new BorderLayout(0, 8));
        private final KotlinInlineTypeAliasRefactoring refactoring;
        private final JRadioButton allOccurrences = new JRadioButton("Inline all occurrences", true);
        private final JRadioButton thisOccurrence = new JRadioButton("Inline this occurrence only");
        private final JCheckBox keepDeclaration = new JCheckBox("Keep the type alias declaration");

        InlineTypeAliasPanel(KotlinInlineTypeAliasRefactoring refactoring, String description) {
            this.refactoring = refactoring;
            JLabel label = new JLabel(description);
            label.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
            component.add(label, BorderLayout.NORTH);

            JPanel options = new JPanel(new GridLayout(0, 1, 0, 4));
            options.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
            ButtonGroup group = new ButtonGroup();
            group.add(allOccurrences);
            group.add(thisOccurrence);
            options.add(allOccurrences);
            options.add(thisOccurrence);
            options.add(keepDeclaration);
            component.add(options, BorderLayout.CENTER);

            allOccurrences.addActionListener(event -> updateOptions());
            thisOccurrence.addActionListener(event -> updateOptions());
            keepDeclaration.addActionListener(event -> updateOptions());
            updateOptions();
        }

        /** Transfers enabled controls to the NetBeans refactoring carrier. */
        private void updateOptions() {
            boolean one = thisOccurrence.isSelected();
            keepDeclaration.setEnabled(!one);
            if (one) {
                keepDeclaration.setSelected(true);
            }
            refactoring.setInlineThisOnly(one);
            refactoring.setKeepDeclaration(keepDeclaration.isSelected());
        }

        @Override public void initialize() { updateOptions(); }
        @Override public Component getComponent() { return component; }
    }
}
