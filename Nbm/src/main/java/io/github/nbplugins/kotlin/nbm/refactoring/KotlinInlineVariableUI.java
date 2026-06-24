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

import javax.swing.event.ChangeListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * {@link RefactoringUI} adapter for the Kotlin Inline Variable refactoring.
 *
 * No custom options panel is needed — the NetBeans refactoring framework shows the affected code
 * in the standard preview panel. The user can review all replacements and the declaration
 * deletion before confirming.
 *
 * @param variableName the name of the variable being inlined, shown in the dialog title
 * @param refactoring  the underlying {@link KotlinInlineVariableRefactoring}
 */
public class KotlinInlineVariableUI implements RefactoringUI {

    private final KotlinInlineVariableRefactoring refactoring;
    private final String variableName;

    /**
     * Creates an Inline Variable UI for the given variable.
     *
     * @param variableName display name of the Kotlin variable being inlined
     * @param refactoring  the {@link KotlinInlineVariableRefactoring} to execute
     */
    public KotlinInlineVariableUI(String variableName, KotlinInlineVariableRefactoring refactoring) {
        this.variableName = variableName;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Inline Variable";
    }

    @Override
    public String getDescription() {
        return "Inline variable '" + variableName + "'";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    /** No custom options panel — the preview panel shows all replacements directly. */
    @Override
    public CustomRefactoringPanel getPanel(ChangeListener parent) {
        return null;
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
        return false;
    }

    @Override
    public AbstractRefactoring getRefactoring() {
        return refactoring;
    }

    @Override
    public HelpCtx getHelpCtx() {
        return null;
    }
}
