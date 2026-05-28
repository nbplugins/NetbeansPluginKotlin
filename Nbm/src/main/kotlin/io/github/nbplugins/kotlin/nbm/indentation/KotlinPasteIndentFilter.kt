/*******************************************************************************
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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.indentation

import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.Document
import javax.swing.text.DocumentFilter
import javax.swing.text.StyledDocument
import org.jetbrains.kotlin.formatting.KotlinIndentStrategy

/**
 * [DocumentFilter] that adjusts the indentation of multi-line text pasted into a Kotlin
 * editor. Installed on the [AbstractDocument] by [installPasteFilterIfNeeded] the first
 * time [KotlinParser][org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParser]
 * parses a file. The pre-existing filter (typically
 * `CloneableEditorSupport$DocFilter`) is kept as the delegate so that guarded-section
 * protection and other NetBeans-level rules remain intact.
 *
 * The actual indentation adjustment is performed by [computePasteAdjustment]; this filter
 * only wires it into the document's insert/replace pipeline and forwards to the delegate.
 */
class KotlinPasteIndentFilter(private val delegate: DocumentFilter?) : DocumentFilter() {

    override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
        val adjusted = text?.let { computePasteAdjustment(fb.document, offset, it) }
        if (adjusted != null) {
            delegate?.replace(fb, offset, length, adjusted, attrs) ?: fb.replace(offset, length, adjusted, attrs)
        } else {
            delegate?.replace(fb, offset, length, text, attrs) ?: fb.replace(offset, length, text, attrs)
        }
    }

    override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
        val adjusted = string?.let { computePasteAdjustment(fb.document, offset, it) }
        if (adjusted != null) {
            delegate?.insertString(fb, offset, adjusted, attr) ?: fb.insertString(offset, adjusted, attr)
        } else {
            delegate?.insertString(fb, offset, string, attr) ?: fb.insertString(offset, string, attr)
        }
    }

    override fun remove(fb: FilterBypass, offset: Int, length: Int) {
        delegate?.remove(fb, offset, length) ?: fb.remove(offset, length)
    }
}

/**
 * Returns the indentation-adjusted version of [text] when it is a multi-line paste
 * inserted at the start of a line, or null if no adjustment is needed or possible.
 *
 * The target indentation for the first pasted line is the indent NetBeans would assign
 * by pressing Enter on the preceding non-empty line ([KotlinIndentStrategy.computeIndentForPaste]).
 * The delta (target − actual first-line indent) is then applied to every line of the
 * pasted text via [applyDeltaToText], preserving the relative indentation inside the block.
 */
internal fun computePasteAdjustment(document: Document, offset: Int, text: String): String? {
    val firstNewline = text.indexOf('\n')
    // firstNewline == 0 means paste starts with '\n' (plain Enter) — skip.
    // firstNewline == -1 means single-line paste — still needs indent adjustment.
    if (firstNewline == 0) return null

    val doc = document as? StyledDocument ?: return null
    val docText = try { doc.getText(0, doc.getLength()) } catch (_: BadLocationException) { return null }

    // Only shift when insertion is at the start of a line (only whitespace before offset on same line).
    val lineStart = if (offset > 0) docText.lastIndexOf('\n', offset - 1) + 1 else 0
    val prefix = docText.substring(lineStart, offset)
    if (prefix.isNotBlank()) return null

    val expectedIndent = try {
        KotlinIndentStrategy(doc, offset).computeIndentForPaste()
    } catch (_: BadLocationException) {
        return null
    }
    if (expectedIndent.isEmpty()) return null

    val pasteFirstLine = if (firstNewline < 0) text else text.substring(0, firstNewline)
    val pasteFirstIndent = pasteFirstLine.takeWhile { it == ' ' || it == '\t' }
    val delta = indentWidth(expectedIndent) - indentWidth(pasteFirstIndent)
    if (delta == 0) return null

    return applyDeltaToText(text, delta)
}

/**
 * Adjusts the leading whitespace of every line of [text] by [delta] columns.
 * Blank lines are replaced with an empty string regardless of delta.
 * Tabs count as 8 spaces; output uses spaces only.
 */
internal fun applyDeltaToText(text: String, delta: Int): String =
    text.split("\n").joinToString("\n") { line ->
        if (line.isBlank()) {
            ""
        } else {
            val ws = line.takeWhile { it == ' ' || it == '\t' }
            val newWidth = maxOf(0, indentWidth(ws) + delta)
            " ".repeat(newWidth) + line.drop(ws.length)
        }
    }

/**
 * Installs a [KotlinPasteIndentFilter] as the outermost [DocumentFilter] on [doc],
 * wrapping any pre-existing filter as its delegate. Safe to call repeatedly — installs
 * only once per document instance.
 */
internal fun installPasteFilterIfNeeded(doc: Document) {
    if (doc !is AbstractDocument) return
    val existing = doc.documentFilter
    if (existing is KotlinPasteIndentFilter) return
    doc.documentFilter = KotlinPasteIndentFilter(existing)
}
