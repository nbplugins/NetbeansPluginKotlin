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
 * {@link RefactoringUI} adapter for the Kotlin **Inline Function** refactoring.
 *
 * No custom options panel is needed for the MVP — the preview pane shows every call site as a
 * separate row and the user confirms via the standard Refactor / Cancel buttons (always inlines
 * all occurrences). An "Inline this only" option can be added as a future enhancement.
 *
 * @param symbolName  short name of the function being inlined; shown in the dialog title
 * @param refactoring carrier {@link KotlinInlineFunctionRefactoring} routed to
 *                    {@link KotlinInlineFunctionPlugin} by
 *                    {@code org.jetbrains.kotlin.refactorings.rename.KotlinRefactoringsFactory}
 */
public class KotlinInlineFunctionUI implements RefactoringUI {

    private final KotlinInlineFunctionRefactoring refactoring;
    private final String symbolName;

    /**
     * Creates an Inline Function UI for the given function.
     *
     * @param symbolName  display name of the function being inlined (may be empty when not yet known)
     * @param refactoring the carrier {@link KotlinInlineFunctionRefactoring}
     */
    public KotlinInlineFunctionUI(String symbolName, KotlinInlineFunctionRefactoring refactoring) {
        this.symbolName = symbolName;
        this.refactoring = refactoring;
    }

    @Override
    public String getName() {
        return "Inline Function";
    }

    @Override
    public String getDescription() {
        if (symbolName == null || symbolName.isEmpty()) return "Inline Function";
        return "Inline function '" + symbolName + "'";
    }

    @Override
    public boolean isQuery() {
        return false;
    }

    /** No custom options panel — preview panel shows every call site that will be inlined. */
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
