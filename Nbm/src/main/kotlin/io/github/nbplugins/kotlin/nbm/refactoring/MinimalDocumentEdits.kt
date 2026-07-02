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
import javax.swing.text.Document

/**
 * Applies an *introduce*-style transformation (Introduce Variable / Introduce Constant) to a
 * document as **minimal, targeted edits** rather than a whole-document replace.
 *
 * Whole-document replacement (`remove(0, length)` + `insert(0, wholeNewText)`) records a single
 * huge undoable edit; a native editor Undo then re-inserts the entire original text and leaves the
 * caret at the end of the document. Making small local edits instead lets the editor's own Undo
 * reverse them and keep the caret at the edit site — the behaviour users expect from ordinary
 * editing.
 */
internal object MinimalDocumentEdits {

    /**
     * Replaces each range in [replacements] with [replacementText], then optionally inserts
     * [insertText] at [insertOffset].
     *
     * @param doc             the target document; edits should be wrapped by the caller in the
     *                        document's atomic lock so they group into one undoable unit
     * @param replacements    ranges to replace, **sorted descending by `startOffset`** so that each
     *                        edit leaves the offsets of the not-yet-edited (earlier) ranges valid
     * @param replacementText the text each range is replaced with (the chosen variable/constant name)
     * @param insertOffset    position (in post-replacement coordinates) to insert the declaration at,
     *                        or `null` to insert nothing; clamped to the document length
     * @param insertText      the declaration text to insert, or `null` to insert nothing
     */
    fun apply(
        doc: Document,
        replacements: List<TextRange>,
        replacementText: String,
        insertOffset: Int?,
        insertText: String?,
    ) {
        // 1. Replace each occurrence, back-to-front.
        for (range in replacements) {
            doc.remove(range.startOffset, range.length)
            doc.insertString(range.startOffset, replacementText, null)
        }
        // 2. Insert the declaration, if any (offset is in post-replacement space).
        if (insertOffset != null && insertText != null) {
            doc.insertString(insertOffset.coerceIn(0, doc.length), insertText, null)
        }
    }
}
