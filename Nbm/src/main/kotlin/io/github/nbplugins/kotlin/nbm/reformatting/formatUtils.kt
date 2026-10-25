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
 *
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.reformatting

import com.intellij.openapi.util.TextRange
import io.github.nbplugins.kotlin.nbm.document.TextRangeDiff
import javax.swing.text.Document
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage
import io.github.nbplugins.kotlin.nbm.navigation.moveCaretToOffset
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.project.Project
import org.netbeans.api.project.ui.OpenProjects
import org.openide.text.NbDocument
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.text.StyledDocument
import javax.swing.SwingUtilities


/** Controls whether formatting explicitly navigates the editor after updating the document. */
enum class FormatCaretPolicy {
    /** Move the editor caret to the requested formatting offset. */
    MOVE_TO_OFFSET,

    /** Leave caret ownership to the calling operation. */
    PRESERVE,
}

/**
 * Formats [doc], writing only the changed ranges of the formatted result back into it.
 *
 * When [startOffset] and [endOffset] bound a non-empty selection (`startOffset < endOffset`),
 * only that character range is reformatted and the rest of the document is left unchanged.
 * When no range is provided the entire document is formatted.
 *
 * @param doc document whose Kotlin text is formatted
 * @param offset editor destination used when [caretPolicy] is [FormatCaretPolicy.MOVE_TO_OFFSET]
 * @param startOffset optional beginning of the formatting range
 * @param endOffset optional end of the formatting range
 * @param proj project whose Kotlin code-style settings apply
 * @param caretPolicy whether formatting should explicitly navigate an editor caret
 */
fun format(
    doc: Document,
    offset: Int,
    startOffset: Int = -1,
    endOffset: Int = -1,
    proj: Project? = null,
    caretPolicy: FormatCaretPolicy = FormatCaretPolicy.MOVE_TO_OFFSET,
) {
    val file = ProjectUtils.getFileObjectForDocument(doc)
    if (file == null) {
        formatPreview(doc, offset, startOffset, endOffset)
        return
    }

    // Use the document's current text directly so that edits applied just before
    // format() are not lost if the PSI cache still holds the pre-edit version.
    val currentText = doc.getText(0, doc.length)
    val project = proj ?: ProjectUtils.getKotlinProjectForFileObject(file)
    var formattedCode = ""
    // Push per-project (or global) settings once before any formatter call so that
    // buildModel() and getSettings() always return the right settings without per-call I/O.
    KotlinFormatterUtils.pushSettings(ProjectCodeStyleStorage.getSettings(project))
    try {
        formattedCode = if (startOffset >= 0 && endOffset > startOffset) {
            val psiFactory = KotlinFormatterUtils.createPsiFactory(project)
            // Strip trailing newlines from the selection end so the IntelliJ formatter
            // does not bleed into the first line of the statement that follows the selection.
            var trimmedEnd = endOffset
            while (trimmedEnd > startOffset && currentText[trimmedEnd - 1] == '\n') trimmedEnd--
            KotlinFormatterUtils.formatRange(currentText, TextRange(startOffset, trimmedEnd), psiFactory, file.name)
        } else {
            KotlinFormatterUtils.formatCode(currentText, file.name, project, "\n")
        }
    } finally {
        KotlinFormatterUtils.popSettings()
    }
    applyFormattedText(doc, currentText, formattedCode)
    applyFormatCaretPolicy(doc, offset, caretPolicy)
}

/**
 * Applies formatter output as the smallest independent edits instead of resetting the entire document.
 *
 * A whole-document removal collapses editor positions and makes NetBeans move a caret to the end of
 * the reinserted text. Descending hunks leave every unchanged position owned by the editor intact.
 */
internal fun applyFormattedText(doc: Document, originalText: String, formattedText: String) {
    val hunks = TextRangeDiff.computeHunks(originalText, formattedText)
        .sortedByDescending { it.oldStart }
    if (hunks.isEmpty()) return

    if (FORMATTER_LOGGER.isLoggable(Level.FINE)) FORMATTER_LOGGER.log(
        Level.FINE,
        "Applying formatter hunks: document={0}@{1}, oldLength={2}, newLength={3}, hunks={4}",
        arrayOf(doc.javaClass.name, System.identityHashCode(doc), originalText.length, formattedText.length, hunks.size),
    )
    val apply = {
        hunks.forEach { hunk ->
            if (FORMATTER_LOGGER.isLoggable(Level.FINEST)) {
                FORMATTER_LOGGER.log(
                    Level.FINEST,
                    "Applying formatter hunk: old=[{0},{1}), new=[{2},{3})",
                    arrayOf(hunk.oldStart, hunk.oldEnd, hunk.newStart, hunk.newEnd),
                )
            }
            if (hunk.oldEnd > hunk.oldStart) {
                doc.remove(hunk.oldStart, hunk.oldEnd - hunk.oldStart)
            }
            val replacement = formattedText.substring(hunk.newStart, hunk.newEnd)
            if (replacement.isNotEmpty()) {
                doc.insertString(hunk.oldStart, replacement, null)
            }
        }
    }
    if (doc is StyledDocument) {
        NbDocument.runAtomicAsUser(doc, apply)
    } else {
        apply()
    }
}

/** Applies the formatter's explicit editor-navigation policy after its text update. */
internal fun applyFormatCaretPolicy(
    doc: Document,
    offset: Int,
    caretPolicy: FormatCaretPolicy,
    moveCaret: Document.(Int) -> Unit = Document::moveCursorTo,
) {
    if (caretPolicy == FormatCaretPolicy.MOVE_TO_OFFSET) doc.moveCaret(offset)
}

/** Queues navigation of this document's editor caret to [position]. */
fun Document.moveCursorTo(position: Int) = SwingUtilities.invokeLater { moveCaretToOffset(this as StyledDocument, position) }

private val FORMATTER_LOGGER: Logger = Logger.getLogger("io.github.nbplugins.kotlin.nbm.reformatting")

/**
 * Formats a project-less in-memory document (scratch buffers, doc-less Kotlin
 * snippets).
 *
 * Borrows a [KtPsiFactory] from any open Kotlin project so a [KtFile] can be
 * built without a `FileObject` and without bootstrapping a new
 * `KotlinEnvironment`. If no Kotlin project is open the document is left
 * unchanged. Settings come from the global `KotlinFormatterUtils` singleton,
 * which is loaded from `KotlinCodeStylePreferences` on plugin startup.
 */
private fun formatPreview(doc: Document, offset: Int, startOffset: Int, endOffset: Int) {
    val anyProject = OpenProjects.getDefault().openProjects.firstOrNull() ?: return
    val currentText = doc.getText(0, doc.length)
    val formattedCode = if (startOffset >= 0 && endOffset > startOffset) {
        val psiFactory = KotlinFormatterUtils.createPsiFactory(anyProject)
        var trimmedEnd = endOffset
        while (trimmedEnd > startOffset && currentText[trimmedEnd - 1] == '\n') trimmedEnd--
        KotlinFormatterUtils.formatRange(currentText, TextRange(startOffset, trimmedEnd), psiFactory, "preview.kt")
    } else {
        KotlinFormatterUtils.formatCode(currentText, "preview.kt", anyProject, "\n")
    }
    applyFormattedText(doc, currentText, formattedCode)
}
