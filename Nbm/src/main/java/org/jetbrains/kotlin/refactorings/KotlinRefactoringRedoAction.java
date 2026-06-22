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
package org.jetbrains.kotlin.refactorings;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.Action;
import org.openide.awt.UndoRedo;

/**
 * Counterpart of {@link KotlinRefactoringUndoAction} — re-applies the most recently undone
 * refactoring session via the same {@link UndoRedo} service.
 */
public class KotlinRefactoringRedoAction extends AbstractAction {

    /** Public default constructor required by NetBeans action instantiation. */
    public KotlinRefactoringRedoAction() {
        putValue(Action.NAME, "Redo Last Refactoring");
    }

    @Override
    public boolean isEnabled() {
        UndoRedo ur = KotlinRefactoringUndoAction.lookupRefactoringUndoRedo();
        return ur != null && ur.canRedo();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        UndoRedo ur = KotlinRefactoringUndoAction.lookupRefactoringUndoRedo();
        if (ur != null && ur.canRedo()) {
            ur.redo();
        }
    }
}
