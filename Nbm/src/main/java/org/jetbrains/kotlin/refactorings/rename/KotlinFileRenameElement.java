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

import io.github.nbplugins.kotlin.refactoring.TextChange;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation;
import org.openide.cookies.EditorCookie;
import org.openide.cookies.SaveCookie;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.text.NbDocument;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

/**
 * A refactoring element that applies all rename {@link TextChange}s for a single file atomically.
 *
 * <p>Changes are applied through the file's {@link StyledDocument} so the open editor sees them
 * live (semantic highlighting, Navigator structure, and the parser all stay in sync), and the
 * document's own {@link org.openide.awt.UndoRedo} naturally records a single edit group that
 * Ctrl+Z reverts.
 *
 * @param fo      the file to modify
 * @param changes all rename text changes for this file (offset ranges + replacement text)
 */
public class KotlinFileRenameElement extends SimpleRefactoringElementImplementation {

    private final FileObject fo;
    private final List<TextChange> changes;
    /** Snapshot of the document text before {@link #performChange()}, used by {@link #undoChange()}. */
    private String originalContent;

    /**
     * Creates a new element for {@code fo} that will apply {@code changes} when
     * {@link #performChange()} is called.
     *
     * @param fo      the Kotlin source file that contains the rename occurrences
     * @param changes all {@link TextChange}s to apply to {@code fo}, in any order
     */
    public KotlinFileRenameElement(FileObject fo, List<TextChange> changes) {
        this.fo = fo;
        this.changes = changes;
    }

    @Override
    public String getText() {
        return fo.getNameExt();
    }

    @Override
    public String getDisplayText() {
        return fo.getNameExt();
    }

    /**
     * Opens the file's document, applies all changes inside a single
     * {@link NbDocument#runAtomicAsUser} block (so they group as one undo entry), and saves.
     */
    @Override
    public void performChange() {
        try {
            DataObject dob = DataObject.find(fo);
            EditorCookie ec = dob.getCookie(EditorCookie.class);
            if (ec == null) {
                return;
            }
            StyledDocument doc = ec.openDocument();
            originalContent = doc.getText(0, doc.getLength());
            List<TextChange> sorted = changes.stream()
                    .sorted(Comparator.comparingInt(TextChange::getStart).reversed())
                    .collect(Collectors.toList());
            NbDocument.runAtomicAsUser(doc, () -> {
                try {
                    for (TextChange c : sorted) {
                        doc.remove(c.getStart(), c.getEnd() - c.getStart());
                        doc.insertString(c.getStart(), c.getNewText(), null);
                    }
                } catch (BadLocationException ex) {
                    Exceptions.printStackTrace(ex);
                }
            });
            SaveCookie save = dob.getCookie(SaveCookie.class);
            if (save != null) {
                save.save();
            }
        } catch (Exception ex) {
            Exceptions.printStackTrace(ex);
        }
    }

    /**
     * Reverts the file to its pre-rename content via the document, used by the framework-level
     * Refactoring → Undo menu (cross-file rollback). Editor Ctrl+Z is handled separately and
     * naturally by the per-document undo stack recorded during {@link #performChange()}.
     */
    @Override
    public void undoChange() {
        if (originalContent == null) {
            return;
        }
        try {
            DataObject dob = DataObject.find(fo);
            EditorCookie ec = dob.getCookie(EditorCookie.class);
            if (ec == null) {
                return;
            }
            StyledDocument doc = ec.openDocument();
            String snapshot = originalContent;
            NbDocument.runAtomicAsUser(doc, () -> {
                try {
                    doc.remove(0, doc.getLength());
                    doc.insertString(0, snapshot, null);
                } catch (BadLocationException ex) {
                    Exceptions.printStackTrace(ex);
                }
            });
            SaveCookie save = dob.getCookie(SaveCookie.class);
            if (save != null) {
                save.save();
            }
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

    /** Returns {@code null} — no single position represents a multi-occurrence element. */
    @Override
    public org.openide.text.PositionBounds getPosition() {
        return null;
    }
}
