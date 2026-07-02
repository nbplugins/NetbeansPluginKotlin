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
package io.github.nbplugins.kotlin.nbm.refactoring

import org.netbeans.api.editor.document.CustomUndoDocument
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import javax.swing.SwingUtilities
import javax.swing.text.Document
import javax.swing.undo.AbstractUndoableEdit

/**
 * Caret-restoration support for refactorings that edit the document directly.
 *
 * A native editor Undo (Ctrl+Z) reverses the document edits a refactoring made but does **not**
 * call the Refactoring SPI's `undoChange()`; afterwards the editor's own queued caret handling
 * (`EditorCaret`) snaps the caret to the end of the reverted document. To restore the caret to
 * where the user triggered the refactoring, a [CaretRestoreOnUndoEdit] is joined to the same atomic
 * undo group as the text edits, and its `undo()` repositions the caret.
 */

/**
 * Joins a caret-restoring edit to [doc]'s current atomic undo compound so that a native Ctrl+Z
 * restores the caret to [caretOffset] instead of leaving it at the end of the document.
 *
 * Must be called inside the document's atomic lock so the edit lands in the same undo group as the
 * text edits. A no-op when [doc] is not a [CustomUndoDocument] (e.g. a plain document in tests).
 *
 * @param doc         the document being edited
 * @param fo          the file whose editor pane should be repositioned
 * @param caretOffset the pre-refactoring caret offset to restore on undo
 */
internal fun joinCaretRestoreOnUndo(doc: Document, fo: FileObject, caretOffset: Int) {
    (doc as? CustomUndoDocument)?.addUndoableEdit(
        CaretRestoreOnUndoEdit { restoreCaret(fo, caretOffset) },
    )
}

/**
 * A zero-width undoable edit that carries no document change; its [undo] runs [restore] to
 * reposition the caret. The call is **double-deferred** via [SwingUtilities.invokeLater] so it
 * executes after the editor's own post-undo caret handling (which would otherwise leave the caret
 * at the end of the reverted document), winning the EDT race regardless of edit ordering.
 *
 * @param restore action that repositions the caret; invoked on the EDT
 */
internal class CaretRestoreOnUndoEdit(private val restore: () -> Unit) : AbstractUndoableEdit() {
    override fun undo() {
        super.undo()
        SwingUtilities.invokeLater { SwingUtilities.invokeLater(restore) }
    }
}

/** Sets the caret of [fo]'s first opened editor pane to [offset], clamped to the document. */
internal fun restoreCaret(fo: FileObject, offset: Int) {
    runCatching {
        val ec = DataObject.find(fo).lookup.lookup(EditorCookie::class.java) ?: return
        val pane = ec.openedPanes?.firstOrNull() ?: return
        pane.caretPosition = offset.coerceIn(0, pane.document.length)
    }
}
