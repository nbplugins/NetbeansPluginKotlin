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

import java.awt.BorderLayout;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.ChangeListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * NetBeans UI adapter for Kotlin **Inline Anonymous Function**.
 *
 * The transformation is limited to the immediately invoked lambda or anonymous function selected
 * at the caret. IDEA's K2 processor supplies the actual rewrite; NetBeans supplies its preview
 * and confirmation flow.
 *
 * @param refactoring carrier routed to {@link KotlinInlineAnonymousFunctionPlugin}
 */
public final class KotlinInlineAnonymousFunctionUI implements RefactoringUI {

    private final KotlinInlineAnonymousFunctionRefactoring refactoring;

    /**
     * Creates an Inline Anonymous Function UI.
     *
     * @param refactoring carrier receiving the active document and caret
     */
    public KotlinInlineAnonymousFunctionUI(KotlinInlineAnonymousFunctionRefactoring refactoring) {
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Inline Anonymous Function";
    }

    @Override
    public String getDescription() {
        return "Inline immediately invoked lambda or anonymous function";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        return new DescriptionPanel(getDescription());
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

    /** Displays the operation description until the user previews or confirms the refactoring. */
    private static final class DescriptionPanel implements CustomRefactoringPanel {
        private final JPanel component;

        DescriptionPanel(String description) {
            JLabel label = new JLabel(description);
            label.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            component = new JPanel(new BorderLayout());
            component.add(label, BorderLayout.CENTER);
        }

        @Override public void initialize() {}
        @Override public Component getComponent() { return component; }
    }
}
