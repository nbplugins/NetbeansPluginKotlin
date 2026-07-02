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

import com.intellij.openapi.util.TextRange
import org.netbeans.junit.NbTestCase
import javax.swing.event.UndoableEditListener
import javax.swing.text.PlainDocument
import javax.swing.undo.CompoundEdit

/**
 * Unit tests for [MinimalDocumentEdits] — the minimal, targeted document-edit strategy shared by
 * Introduce Variable and Introduce Constant.
 *
 * The key property under test is that the transformation is applied as small local edits (never a
 * whole-document replace), so that a **single** compound Undo — modelling the editor's native
 * Ctrl+Z over the refactoring's atomic transaction — restores the exact original text. Every edit
 * generated during [MinimalDocumentEdits.apply] is grouped into one [CompoundEdit], mirroring the
 * document atomic lock used in production.
 */
class MinimalDocumentEditsTest : NbTestCase("MinimalDocumentEditsTest") {

    /** Text of the whole document. */
    private fun PlainDocument.text(): String = getText(0, length)

    /**
     * Runs [block] against a fresh [PlainDocument] initialised with [initial], collecting every
     * undoable edit it produces into one [CompoundEdit] (as the atomic lock does in production).
     *
     * @return the document and the closed compound edit
     */
    private fun withDoc(initial: String, block: (PlainDocument) -> Unit): Pair<PlainDocument, CompoundEdit> {
        val doc = PlainDocument()
        doc.insertString(0, initial, null)
        val compound = CompoundEdit()
        val listener = UndoableEditListener { e -> compound.addEdit(e.edit) }
        doc.addUndoableEditListener(listener)
        block(doc)
        compound.end()
        doc.removeUndoableEditListener(listener)
        return doc to compound
    }

    /** Ranges of every occurrence of [needle] in [text], sorted descending by start offset. */
    private fun occurrencesDescending(text: String, needle: String): List<TextRange> {
        val ranges = mutableListOf<TextRange>()
        var idx = text.indexOf(needle)
        while (idx >= 0) {
            ranges.add(TextRange(idx, idx + needle.length))
            idx = text.indexOf(needle, idx + needle.length)
        }
        return ranges.sortedByDescending { it.startOffset }
    }

    /**
     * Introduce Variable shape: one occurrence replaced with the chosen name plus a declaration
     * line inserted before it. Verifies both the resulting text and that a single compound undo
     * restores the original exactly.
     */
    fun testReplaceAndInsert_producesExpectedText_andUndoRestores() {
        val original = "fun f() {\n    println(EXPR)\n}\n"
        val exprStart = original.indexOf("EXPR")
        val exprEnd = exprStart + "EXPR".length
        val lineStart = original.lastIndexOf('\n', exprStart - 1) + 1
        val declaration = "    val value = EXPR\n"

        val (doc, compound) = withDoc(original) { d ->
            MinimalDocumentEdits.apply(d, listOf(TextRange(exprStart, exprEnd)), "value", lineStart, declaration)
        }

        val expected = "fun f() {\n    val value = EXPR\n    println(value)\n}\n"
        assertEquals("minimal edits must produce the introduce-variable result", expected, doc.text())

        compound.undo()
        assertEquals("a single (compound) undo must restore the original text", original, doc.text())
    }

    /**
     * Replace-all shape with three occurrences and no declaration insert: all occurrences are
     * replaced (back-to-front) and a single compound undo restores the original.
     */
    fun testMultipleOccurrences_replacedBackToFront_andUndoRestores() {
        val original = "val a = X\nval b = X\nval c = X\n"
        val ranges = occurrencesDescending(original, "X")
        assertEquals("fixture must have three occurrences", 3, ranges.size)

        val (doc, compound) = withDoc(original) { d ->
            MinimalDocumentEdits.apply(d, ranges, "NAME", insertOffset = null, insertText = null)
        }

        assertEquals("all occurrences replaced", "val a = NAME\nval b = NAME\nval c = NAME\n", doc.text())

        compound.undo()
        assertEquals("undo restores the original", original, doc.text())
    }

    /**
     * Insert-only shape (no replacements): only the declaration is inserted, and undo removes it.
     */
    fun testInsertOnly_noReplacements_andUndoRestores() {
        val original = "class C {\n}\n"
        val (doc, compound) = withDoc(original) { d ->
            MinimalDocumentEdits.apply(d, replacements = emptyList(), replacementText = "unused", insertOffset = 0, insertText = "const val N = 1\n\n")
        }

        assertEquals("declaration inserted at the top", "const val N = 1\n\nclass C {\n}\n", doc.text())

        compound.undo()
        assertEquals("undo removes the inserted declaration", original, doc.text())
    }

    /**
     * Regression: the minimal-edit result must be byte-identical to the previous whole-document
     * replacement (replacements applied back-to-front, then the declaration spliced in at the
     * post-replacement offset).
     */
    fun testMinimalEdits_matchWholeReplaceResult() {
        val original = "fun f() {\n    g(P) + g(P)\n}\n"
        val chosenName = "n"
        val ranges = occurrencesDescending(original, "P")
        val insertOffset = 0
        val insertText = "val n = P\n"

        // Reference: the old whole-document-replace computation.
        var reference = original
        for (r in ranges) {
            reference = reference.substring(0, r.startOffset) + chosenName + reference.substring(r.endOffset)
        }
        reference = reference.substring(0, insertOffset) + insertText + reference.substring(insertOffset)

        val (doc, _) = withDoc(original) { d ->
            MinimalDocumentEdits.apply(d, ranges, chosenName, insertOffset, insertText)
        }

        assertEquals("minimal edits must equal the whole-replace result", reference, doc.text())
    }

    /**
     * An out-of-range insert offset is clamped to the document length rather than throwing.
     */
    fun testInsertOffsetBeyondLength_isClamped() {
        val original = "abc"
        val (doc, compound) = withDoc(original) { d ->
            MinimalDocumentEdits.apply(d, emptyList(), "x", insertOffset = 9999, insertText = "Z")
        }

        assertEquals("insert clamped to end of document", "abcZ", doc.text())

        compound.undo()
        assertEquals("undo restores the original", original, doc.text())
    }
}
