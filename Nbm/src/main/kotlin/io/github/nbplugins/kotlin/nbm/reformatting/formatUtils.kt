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
import javax.swing.text.Document
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage
import io.github.nbplugins.kotlin.nbm.navigation.moveCaretToOffset
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.project.Project
import org.netbeans.api.project.ui.OpenProjects
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
    doc.remove(0, doc.length)
    doc.insertString(0, formattedCode, null)
    doc.moveCursorTo(offset)
}

fun Document.moveCursorTo(position: Int) = SwingUtilities.invokeLater { moveCaretToOffset(this as StyledDocument, position) }

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
    doc.remove(0, doc.length)
    doc.insertString(0, formattedCode, null)
}