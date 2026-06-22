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
package org.jetbrains.kotlin.refactorings.rename;

import java.awt.Container;
import javax.swing.JEditorPane;
import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation;
import org.openide.awt.UndoRedo;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.windows.TopComponent;

/**
 * A refactoring element that renames the underlying {@link DataObject} of a Kotlin file and
 * records the rename in the file's editor undo stack.
 *
 * <p>Used when a Kotlin class whose name matches the containing file is renamed: the file should
 * be renamed to {@code newName.kt} to preserve the one-class-per-file convention. Must be added
 * to the refactoring bag AFTER the corresponding {@link KotlinFileRenameElement} so content
 * edits land in the document undo stack first; the file rename then lands on top so Ctrl+Z in
 * the open editor reverts the rename first, then the content edit.
 *
 * @param fo      the file to rename (the same {@link FileObject} used in the content element)
 * @param oldName the current file name without extension (must equal the class's old name)
 * @param newName the new file name without extension (the class's new name)
 */
public class KotlinFileObjectRenameElement extends SimpleRefactoringElementImplementation {

    private final FileObject fo;
    private final String oldName;
    private final String newName;

    /**
     * Creates an element that renames {@code fo} from {@code oldName.kt} to {@code newName.kt}.
     *
     * @param fo      the source file to rename
     * @param oldName the current base name (without extension)
     * @param newName the new base name (without extension)
     */
    public KotlinFileObjectRenameElement(FileObject fo, String oldName, String newName) {
        this.fo = fo;
        this.oldName = oldName;
        this.newName = newName;
    }

    @Override
    public String getText() {
        return "Rename file " + oldName + "." + fo.getExt() + " → " + newName + "." + fo.getExt();
    }

    @Override
    public String getDisplayText() {
        return getText();
    }

    /**
     * Renames the file via {@link DataObject#rename} and, if an editor TopComponent is open for
     * it, registers a custom {@link AbstractUndoableEdit} in that editor's {@link UndoRedo.Manager}
     * so Ctrl+Z reverts the rename (and a subsequent Ctrl+Z reverts the content edit beneath it).
     */
    @Override
    public void performChange() {
        try {
            DataObject dob = DataObject.find(fo);
            UndoRedo.Manager manager = findUndoManager(dob);
            dob.rename(newName);
            if (manager != null) {
                final DataObject finalDob = dob;
                manager.addEdit(new AbstractUndoableEdit() {
                    @Override
                    public void undo() throws CannotUndoException {
                        super.undo();
                        renameDataObject(finalDob, oldName);
                    }

                    @Override
                    public void redo() throws CannotRedoException {
                        super.redo();
                        renameDataObject(finalDob, newName);
                    }

                    @Override
                    public String getPresentationName() {
                        return "Rename file " + oldName + " → " + newName;
                    }
                });
            }
        } catch (Exception ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    /** Framework-level revert: rename back to {@code oldName}. */
    @Override
    public void undoChange() {
        try {
            renameDataObject(DataObject.find(fo), oldName);
        } catch (Exception ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    @Override
    public Lookup getLookup() {
        return Lookups.fixed();
    }

    @Override
    public FileObject getParentFile() {
        return fo;
    }

    @Override
    public org.openide.text.PositionBounds getPosition() {
        return null;
    }

    /**
     * Walks up from any opened editor pane of {@code dob} to its enclosing {@link TopComponent}
     * and returns its {@link UndoRedo.Manager}, or {@code null} if the file has no open editor.
     */
    private static UndoRedo.Manager findUndoManager(DataObject dob) {
        EditorCookie ec = dob.getCookie(EditorCookie.class);
        if (ec == null) {
            return null;
        }
        JEditorPane[] panes = ec.getOpenedPanes();
        if (panes == null || panes.length == 0) {
            return null;
        }
        Container c = panes[0].getParent();
        while (c != null && !(c instanceof TopComponent)) {
            c = c.getParent();
        }
        if (!(c instanceof TopComponent)) {
            return null;
        }
        UndoRedo ur = ((TopComponent) c).getUndoRedo();
        return (ur instanceof UndoRedo.Manager) ? (UndoRedo.Manager) ur : null;
    }

    private static void renameDataObject(DataObject dob, String targetName) {
        try {
            dob.rename(targetName);
        } catch (Exception ex) {
            Exceptions.printStackTrace(ex);
        }
    }
}
