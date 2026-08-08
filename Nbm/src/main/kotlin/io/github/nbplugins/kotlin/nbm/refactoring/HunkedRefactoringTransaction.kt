/*******************************************************************************
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

import io.github.nbplugins.kotlin.nbm.reformatting.format
import org.netbeans.api.project.Project
import org.openide.filesystems.FileObject
import org.openide.text.NbDocument

/**
 * Stages [finalText] as minimal, independently formatted document hunks.
 *
 * All text outside [TextRangeDiff.computeHunks] remains untouched. The enclosing transaction keeps
 * the original whole-document snapshot, so a failed later participant or Undo Last Refactoring
 * restores this document exactly.
 *
 * @param file transaction participant to update
 * @param finalText resulting text produced by the K2 refactoring engine
 * @param project Kotlin project whose formatter settings apply to each changed hunk
 */
internal fun KotlinRefactoringTransaction.stageHunkText(
    file: FileObject,
    finalText: String,
    project: Project,
) {
    stageText(file, finalText) { document, originalText, targetText ->
        val hunks = TextRangeDiff.computeHunks(originalText, targetText).sortedByDescending { it.oldStart }
        NbDocument.runAtomicAsUser(document) {
            for (hunk in hunks) {
                if (hunk.oldEnd > hunk.oldStart) document.remove(hunk.oldStart, hunk.oldEnd - hunk.oldStart)
                val replacement = targetText.substring(hunk.newStart, hunk.newEnd)
                if (replacement.isNotEmpty()) document.insertString(hunk.oldStart, replacement, null)
                val formatEnd = hunk.oldStart + replacement.length
                if (formatEnd > hunk.oldStart) {
                    runCatching {
                        format(
                            doc = document,
                            offset = hunk.oldStart,
                            startOffset = hunk.oldStart,
                            endOffset = formatEnd,
                            proj = project,
                        )
                    }
                }
            }
        }
    }
}
