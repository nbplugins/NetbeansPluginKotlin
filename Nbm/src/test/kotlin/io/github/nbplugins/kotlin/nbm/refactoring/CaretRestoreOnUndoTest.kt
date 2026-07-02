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
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileUtil
import javax.swing.SwingUtilities
import javax.swing.text.PlainDocument
import javax.swing.undo.UndoableEdit

/**
 * Unit tests for the caret-restore-on-undo helper ([joinCaretRestoreOnUndo] /
 * [CaretRestoreOnUndoEdit]) shared by Introduce Variable and Introduce Constant.
 *
 * The editor-caret repositioning itself requires a live editor pane and cannot be unit-tested; the
 * pre-refactoring-caret behaviour is verified manually. These tests cover the parts that can be
 * exercised headlessly: that undo runs the (double-deferred) restore action, and that the edit is
 * joined only to a [CustomUndoDocument].
 */
class CaretRestoreOnUndoTest : NbTestCase("CaretRestoreOnUndoTest") {

    /** A fresh in-memory [org.openide.filesystems.FileObject] for lambda capture (never resolved). */
    private fun memoryFile() = FileUtil.createMemoryFileSystem().root.createData("Test.kt")

    /** Drains all currently queued EDT events by posting empty runnables and waiting for them. */
    private fun drainEdt() {
        SwingUtilities.invokeAndWait { }
        SwingUtilities.invokeAndWait { }
    }

    /**
     * [CaretRestoreOnUndoEdit.undo] must run its restore action (after the double `invokeLater`
     * deferral used to beat the editor's own post-undo caret handling).
     */
    fun testUndo_invokesRestoreAction_afterDoubleDeferral() {
        var called = false
        val edit = CaretRestoreOnUndoEdit { called = true }

        edit.undo()
        assertFalse("restore must be deferred, not run synchronously in undo()", called)

        drainEdt()
        assertTrue("restore action must run once the EDT queue drains", called)
    }

    /**
     * [joinCaretRestoreOnUndo] joins exactly one [CaretRestoreOnUndoEdit] when the document is a
     * [CustomUndoDocument].
     */
    fun testJoin_addsCaretRestoreEdit_toCustomUndoDocument() {
        val added = mutableListOf<UndoableEdit>()
        val doc = object : PlainDocument(), CustomUndoDocument {
            override fun addUndoableEdit(edit: UndoableEdit) {
                added.add(edit)
            }
        }

        joinCaretRestoreOnUndo(doc, memoryFile(), caretOffset = 42)

        assertEquals("exactly one edit must be joined", 1, added.size)
        assertTrue("joined edit must be a CaretRestoreOnUndoEdit", added[0] is CaretRestoreOnUndoEdit)
    }

    /**
     * [joinCaretRestoreOnUndo] is a no-op (and must not throw) when the document does not implement
     * [CustomUndoDocument] — e.g. a plain document.
     */
    fun testJoin_isNoOp_whenNotCustomUndoDocument() {
        val doc = PlainDocument()
        doc.insertString(0, "abc", null)

        // Must simply return without throwing.
        joinCaretRestoreOnUndo(doc, memoryFile(), caretOffset = 1)

        assertEquals("document must be untouched", "abc", doc.getText(0, doc.length))
    }
}
