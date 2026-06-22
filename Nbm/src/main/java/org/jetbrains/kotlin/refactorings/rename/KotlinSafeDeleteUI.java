/**
 * *****************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
 ******************************************************************************
 */
package org.jetbrains.kotlin.refactorings.rename;

import javax.swing.event.ChangeListener;
import org.netbeans.modules.refactoring.api.AbstractRefactoring;
import org.netbeans.modules.refactoring.api.Problem;
import org.netbeans.modules.refactoring.api.SafeDeleteRefactoring;
import org.netbeans.modules.refactoring.spi.ui.CustomRefactoringPanel;
import org.netbeans.modules.refactoring.spi.ui.RefactoringUI;
import org.openide.util.HelpCtx;

/**
 * {@link RefactoringUI} adapter for the Kotlin Safe Delete refactoring.
 *
 * No custom panel is needed — the NetBeans refactoring framework shows found usages
 * in the standard preview panel. The user can review usages before confirming deletion.
 *
 * @param symbolName the name of the symbol being deleted, shown in the dialog title
 * @param refactoring the underlying {@link SafeDeleteRefactoring}
 */
public class KotlinSafeDeleteUI implements RefactoringUI {

    private final SafeDeleteRefactoring refactoring;
    private final String symbolName;

    /**
     * Creates a Safe Delete UI for the given symbol.
     *
     * @param symbolName  display name of the Kotlin symbol being deleted
     * @param refactoring the {@link SafeDeleteRefactoring} to execute
     */
    public KotlinSafeDeleteUI(String symbolName, SafeDeleteRefactoring refactoring) {
        this.symbolName = symbolName;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Safe Delete";
    }

    @Override
    public String getDescription() {
        return "Safe Delete '" + symbolName + "'";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    /** No custom options panel — the preview panel shows usages directly. */
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
