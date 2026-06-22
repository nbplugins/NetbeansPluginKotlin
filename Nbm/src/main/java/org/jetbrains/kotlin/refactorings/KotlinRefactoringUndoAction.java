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
import org.openide.util.lookup.Lookups;

/**
 * Action that triggers the framework-level undo of the most recent refactoring session.
 *
 * <p>NetBeans 23 ships no menu item for this, even though the refactoring API tracks each
 * session in {@code org.netbeans.modules.refactoring.spi.impl.UndoManager} and exposes the
 * stack via an {@link UndoRedo} {@code @ServiceProvider} at the path
 * {@code org/netbeans/modules/refactoring}. We bind that service to a menu entry under
 * Refactor menu so the user can roll back all per-file changes of the last refactoring
 * (across multiple open editors and renamed files) in a single click.
 */
public class KotlinRefactoringUndoAction extends AbstractAction {

    /** Public default constructor required by NetBeans action instantiation. */
    public KotlinRefactoringUndoAction() {
        putValue(Action.NAME, "Undo Last Refactoring");
    }

    @Override
    public boolean isEnabled() {
        UndoRedo ur = lookupRefactoringUndoRedo();
        return ur != null && ur.canUndo();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        UndoRedo ur = lookupRefactoringUndoRedo();
        if (ur != null && ur.canUndo()) {
            ur.undo();
        }
    }

    /**
     * Resolves the {@link UndoRedo} service registered by the refactoring API at the path
     * {@code org/netbeans/modules/refactoring}. Returns {@code null} if no provider is found
     * (defensive — should never happen with a standard NetBeans installation).
     */
    static UndoRedo lookupRefactoringUndoRedo() {
        return Lookups.forPath("org/netbeans/modules/refactoring").lookup(UndoRedo.class);
    }
}
