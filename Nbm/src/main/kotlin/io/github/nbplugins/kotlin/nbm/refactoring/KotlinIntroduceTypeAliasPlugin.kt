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

import io.github.nbplugins.kotlin.nbm.navigation.KotlinFindUsagesResultElement
import io.github.nbplugins.kotlin.nbm.navigation.moveCaretToOffset
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaIntroduceTypeAliasComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import org.openide.windows.TopComponent
import javax.swing.SwingUtilities
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that implements the Kotlin **Introduce Type Alias** refactoring.
 *
 * Bridges the NetBeans refactoring framework to [KaIntroduceTypeAliasComputer]:
 *  1. `prepare()` validates the type reference at the caret and populates [bag] with occurrence
 *     elements and a single [KotlinIntroduceTypeAliasApplyElement] that performs the transformation.
 *  2. A fatal [Problem] is returned if the caret is not on a type reference.
 *
 * @param refactoring the carrier [KotlinIntroduceTypeAliasRefactoring]
 */
class KotlinIntroduceTypeAliasPlugin(
    private val refactoring: KotlinIntroduceTypeAliasRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the type reference at the caret and populates [bag] with:
     *  - one [KotlinFindUsagesResultElement] per occurrence (for the preview pane),
     *  - one [KotlinIntroduceTypeAliasApplyElement] that performs the actual transformation.
     *
     * @return a fatal [Problem] if the caret is not on a type reference, `null` otherwise
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaIntroduceTypeAliasComputer(
            ktFile,
            refactoring.caretOffset,
            refactoring.selectionRange,
        )
        return when (val outcome = computer.compute()) {
            is KaIntroduceTypeAliasComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinIntroduceTypeAliasPlugin.prepare: NotApplicable at offset " +
                            "${refactoring.caretOffset} in ${fo.path}"
                )
                null
            }
            is KaIntroduceTypeAliasComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException(
                    "KotlinIntroduceTypeAliasPlugin.prepare: Error", outcome.error
                )
                Problem(true, outcome.error.message ?: "Introduce Type Alias failed")
            }
            is KaIntroduceTypeAliasComputer.Outcome.Ready -> {
                val result = outcome.result
                for (range in result.occurrenceRanges) {
                    runCatching {
                        bag.add(
                            refactoring,
                            KotlinFindUsagesResultElement(
                                OffsetRange(range.startOffset, range.endOffset), fo,
                            ),
                        )
                    }
                }
                bag.add(refactoring, KotlinIntroduceTypeAliasApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that performs the introduce-type-alias transformation.
 *
 * Strategy:
 *  1. Re-runs [KaIntroduceTypeAliasComputer] to get a fresh result.
 *  2. Replaces occurrences back-to-front with [KotlinIntroduceTypeAliasRefactoring.chosenName].
 *  3. Inserts `[visibility] typealias NAME = TYPE` before the target insertion point.
 *  4. Invalidates the K2 session.
 *
 * The change is applied as **targeted document edits** (never a whole-document replace), and a
 * caret-restore edit is joined to the atomic undo group so a native Ctrl+Z keeps the caret at
 * the trigger site. [undoChange] is a snapshot-based fallback for non-editor undo paths.
 *
 * @param declarationFile  the file containing the type reference
 * @param nbProject        the NetBeans project
 * @param refactoring      the carrier holding options chosen by the user
 */
class KotlinIntroduceTypeAliasApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinIntroduceTypeAliasRefactoring,
) : SimpleRefactoringElementImplementation() {

    private var snapshot: String? = null

    override fun getText(): String = "Introduce type alias"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(declarationFile)
    override fun getParentFile(): FileObject = declarationFile

    override fun getPosition(): PositionBounds? = try {
        val dob = DataObject.find(declarationFile)
        val ces = dob.lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        val start = ces.createPositionRef(0, Bias.Forward)
        val end = ces.createPositionRef(0, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    override fun performChange() {
        val activeBefore: TopComponent? = runCatching { TopComponent.getRegistry().activated }.getOrNull()
        try {
            runCatching {
                val fo = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: return@runCatching
                val nbProject2 = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return@runCatching
                val session = KotlinAnalysisAPISession.getSession(nbProject2)
                val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching

                val computer = KaIntroduceTypeAliasComputer(
                    ktFile,
                    refactoring.caretOffset,
                    refactoring.selectionRange,
                )
                val outcome = computer.compute()
                val ready = outcome as? KaIntroduceTypeAliasComputer.Outcome.Ready ?: return@runCatching
                val result = ready.result

                val doc = openDocument(fo) ?: return@runCatching
                val originalText = doc.getText(0, doc.length)
                snapshot = originalText

                val chosenName = refactoring.chosenName.ifBlank { result.suggestedName }
                val visibilityPrefix = when (refactoring.visibility) {
                    "public", "" -> ""
                    else -> "${refactoring.visibility} "
                }
                val aliasDeclaration = result.renderAliasDeclaration(chosenName, visibilityPrefix)

                // Each generic occurrence has its own substituted alias arguments. Preserve that
                // spelling instead of applying one text replacement to every range.
                val replacements = (if (refactoring.replaceAll) {
                    result.occurrenceReplacements
                } else {
                    result.occurrenceReplacements.filter { it.range == result.typeRefRange }
                }).sortedByDescending { it.range.startOffset }
                    .map { it.range to result.replacementFor(it, chosenName) }

                // Post-replacement view of the text, used only to compute the insertion offset.
                var replacedText = originalText
                for ((range, replacement) in replacements) {
                    replacedText = replacedText.substring(0, range.startOffset) +
                            replacement +
                            replacedText.substring(range.endOffset)
                }

                // Compute the adjusted insertion offset after all replacements.
                fun adjustedOffset(rawOffset: Int): Int {
                    var shift = 0
                    for ((range, replacement) in replacements) {
                        if (range.endOffset <= rawOffset) {
                            shift += replacement.length - (range.endOffset - range.startOffset)
                        }
                    }
                    return rawOffset + shift
                }

                val insertPos = adjustedOffset(result.insertOffset)
                val lineStart = replacedText.lastIndexOf('\n', insertPos - 1) + 1
                val insertedText = "$aliasDeclaration\n\n"

                // Apply targeted edits and join a caret-restore edit so a native Ctrl+Z
                // keeps the caret at the trigger site instead of at EOF (see joinCaretRestoreOnUndo).
                val atomicDoc = doc as? org.netbeans.editor.AtomicLockDocument
                val caretTargetOnUndo = minOf(refactoring.caretOffset, originalText.length)
                val body: () -> Unit = {
                    joinCaretRestoreOnUndo(doc, fo, caretTargetOnUndo)
                    replacements.forEach { (range, replacement) ->
                        doc.remove(range.startOffset, range.endOffset - range.startOffset)
                        doc.insertString(range.startOffset, replacement, null)
                    }
                    doc.insertString(lineStart, insertedText, null)
                }
                if (atomicDoc != null) {
                    atomicDoc.atomicLock()
                    try { body() } finally { atomicDoc.atomicUnlock() }
                } else {
                    NbDocument.runAtomicAsUser(doc) { body() }
                }

                // Move caret to the alias name **at the trigger usage site** (where the type
                // reference was), not into the inserted declaration. Account for replacements at
                // lower offsets and for the declaration inserted before it.
                val primaryStart = result.typeRefRange.startOffset
                val lowerShift = replacements
                    .filter { (range, _) -> range.endOffset <= primaryStart }
                    .sumOf { (range, replacement) -> replacement.length - (range.endOffset - range.startOffset) }
                val adjustedPrimaryStart = primaryStart + lowerShift
                val nameOffset = adjustedPrimaryStart +
                        if (lineStart <= adjustedPrimaryStart) insertedText.length else 0
                SwingUtilities.invokeLater {
                    runCatching { moveCaretToOffset(doc, minOf(nameOffset, doc.length)) }
                }

                KotlinAnalysisAPISession.invalidate(nbProject)
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException("KotlinIntroduceTypeAliasApplyElement.performChange failed", e)
            }
        } finally {
            activeBefore?.let { tc ->
                SwingUtilities.invokeLater { runCatching { tc.requestActive() } }
            }
        }
    }

    override fun undoChange() {
        runCatching {
            val fo = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: return@runCatching
            val doc = openDocument(fo) ?: return@runCatching
            val original = snapshot ?: return@runCatching
            NbDocument.runAtomicAsUser(doc) {
                if (doc.length > 0) doc.remove(0, doc.length)
                doc.insertString(0, original, null)
            }
            KotlinAnalysisAPISession.invalidate(nbProject)
        }
    }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }
}
