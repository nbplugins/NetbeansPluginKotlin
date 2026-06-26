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
 * {@link RefactoringUI} adapter for the Kotlin **Inline Variable** refactoring.
 *
 * No custom options panel is needed — the preview pane shows every usage as a separate row and
 * the user confirms via the standard Refactor / Cancel buttons. This mirrors the IDEA "Inline
 * variable" dialog when the "Inline this only" option is not exposed (we always inline all
 * references; controlling that per call is a future enhancement).
 *
 * @param symbolName  short name of the property being inlined; shown in the dialog title
 * @param refactoring carrier {@link KotlinInlineVariableRefactoring} routed to
 *                    {@link KotlinInlineVariablePlugin} by
 *                    {@code org.jetbrains.kotlin.refactorings.rename.KotlinRefactoringsFactory}
 */
public class KotlinInlineVariableUI implements RefactoringUI {

    private final KotlinInlineVariableRefactoring refactoring;
    private final String symbolName;

    /**
     * Creates an Inline Variable UI for the given property.
     *
     * @param symbolName  display name of the property being inlined (may be empty when not yet known)
     * @param refactoring the carrier {@link KotlinInlineVariableRefactoring}
     */
    public KotlinInlineVariableUI(String symbolName, KotlinInlineVariableRefactoring refactoring) {
        this.symbolName = symbolName;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Inline Variable";
    }

    @Override
    public String getDescription() {
        if (symbolName == null || symbolName.isEmpty()) return "Inline Variable";
        return "Inline variable '" + symbolName + "'";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    /**
     * Returns a minimal description panel so the framework waits for the user to click
     * Preview or Refactor rather than auto-advancing to the preview window.
     */
    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        return new InlineDescriptionPanel(getDescription());
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

    /** Minimal panel that keeps the refactoring dialog open until the user acts. */
    private static final class InlineDescriptionPanel implements CustomRefactoringPanel {
        private final JPanel component;

        InlineDescriptionPanel(String description) {
            JLabel label = new JLabel(description);
            label.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            component = new JPanel(new BorderLayout());
            component.add(label, BorderLayout.CENTER);
        }

        @Override public void initialize() {}
        @Override public Component getComponent() { return component; }
    }
}
