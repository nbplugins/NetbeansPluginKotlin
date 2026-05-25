/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.reformatting

import com.intellij.openapi.util.TextRange
import javax.swing.text.Document
import org.jetbrains.kotlin.formatting.KotlinFormatterUtils
import org.jetbrains.kotlin.navigation.netbeans.moveCaretToOffset
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.project.Project
import javax.swing.text.StyledDocument
import javax.swing.SwingUtilities


/**
 * Formats [doc], replacing its content with the formatted result.
 *
 * When [startOffset] and [endOffset] bound a non-empty selection (`startOffset < endOffset`),
 * only that character range is reformatted and the rest of the document is left unchanged.
 * When no range is provided the entire document is formatted.
 */
fun format(doc: Document, offset: Int, startOffset: Int = -1, endOffset: Int = -1, proj: Project? = null) {
    val file = ProjectUtils.getFileObjectForDocument(doc) ?: return

    // Use the document's current text directly so that edits applied just before
    // format() are not lost if the PSI cache still holds the pre-edit version.
    val currentText = doc.getText(0, doc.length)
    val project = proj ?: ProjectUtils.getKotlinProjectForFileObject(file)
    val formattedCode = if (startOffset >= 0 && endOffset > startOffset) {
        val psiFactory = KotlinFormatterUtils.createPsiFactory(project)
        // Strip trailing newlines from the selection end so the IntelliJ formatter
        // does not bleed into the first line of the statement that follows the selection.
        var trimmedEnd = endOffset
        while (trimmedEnd > startOffset && currentText[trimmedEnd - 1] == '\n') trimmedEnd--
        KotlinFormatterUtils.formatRange(currentText, TextRange(startOffset, trimmedEnd), psiFactory, file.name)
    } else {
        KotlinFormatterUtils.formatCode(currentText, file.name, project, "\n")
    }
    doc.remove(0, doc.length)
    doc.insertString(0, formattedCode, null)
    doc.moveCursorTo(offset)
}

fun Document.moveCursorTo(position: Int) = SwingUtilities.invokeLater { moveCaretToOffset(this as StyledDocument, position) }