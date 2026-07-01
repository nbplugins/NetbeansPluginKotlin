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
import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaIntroduceVariableComputer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import org.openide.windows.TopComponent
import javax.swing.SwingUtilities
import javax.swing.text.BadLocationException
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that implements the Kotlin **Introduce Variable** refactoring.
 *
 * Bridges the NetBeans refactoring framework to [KaIntroduceVariableComputer]:
 *  1. `prepare()` resolves the expression at the caret/selection, finds all semantic occurrences,
 *     and populates [bag] with one [KotlinFindUsagesResultElement] per occurrence and a single
 *     [KotlinIntroduceVariableApplyElement] that performs the transformation.
 *  2. If the cursor is not on an extractable expression a fatal [Problem] is returned.
 *  3. The user provides the variable name in [KotlinIntroduceVariableUI]; the apply element reads
 *     the chosen name from [KotlinIntroduceVariableRefactoring.chosenName].
 *
 * @param refactoring the carrier [KotlinIntroduceVariableRefactoring]
 */
class KotlinIntroduceVariablePlugin(
    private val refactoring: KotlinIntroduceVariableRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the expression at the cursor and populates [bag] with:
     *  - one read-only [KotlinFindUsagesResultElement] per occurrence (for the preview pane),
     *  - one [KotlinIntroduceVariableApplyElement] that performs the actual text transformation.
     *
     * @return a fatal [Problem] if the caret is not on an extractable expression, `null` otherwise
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaIntroduceVariableComputer(
            ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
        )
        return when (val outcome = computer.compute()) {
            is KaIntroduceVariableComputer.Outcome.NotApplicable -> null
            is KaIntroduceVariableComputer.Outcome.Error ->
                Problem(true, outcome.error.message ?: "Introduce Variable failed")
            is KaIntroduceVariableComputer.Outcome.Ready -> {
                val result = outcome.result

                for (range in result.occurrenceRanges) {
                    runCatching {
                        bag.add(
                            refactoring,
                            KotlinFindUsagesResultElement(OffsetRange(range.startOffset, range.endOffset), fo),
                        )
                    }
                }

                bag.add(
                    refactoring,
                    KotlinIntroduceVariableApplyElement(fo, nbProject, refactoring),
                )
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that performs the introduce-variable transformation.
 *
 * Strategy:
 *  1. Re-runs [KaIntroduceVariableComputer] to get a fresh result against the current K2 session.
 *  2. Replaces all occurrences with [KotlinIntroduceVariableRefactoring.chosenName] (back-to-front
 *     so earlier offsets remain valid).
 *  3. Inserts `val chosenName = expressionText\n` at the anchor's line start.
 *  4. Writes the result back to the NetBeans document and invalidates the K2 session.
 *
 * Undo restores the pre-refactor snapshot in a single step.
 *
 * @param declarationFile  the file containing the expression (used for preview grouping)
 * @param nbProject        the NetBeans project (for K2 session access and invalidation)
 * @param refactoring      the carrier that holds [KotlinIntroduceVariableRefactoring.chosenName]
 */
class KotlinIntroduceVariableApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinIntroduceVariableRefactoring,
) : SimpleRefactoringElementImplementation() {

    private var snapshot: String? = null

    override fun getText(): String = "Introduce variable"
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

                val computer = KaIntroduceVariableComputer(
                    ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
                )
                val ready = computer.compute() as? KaIntroduceVariableComputer.Outcome.Ready
                    ?: return@runCatching
                val result = ready.result

                val doc = openDocument(fo) ?: return@runCatching
                val originalText = doc.getText(0, doc.length)
                snapshot = originalText

                val chosenName = refactoring.chosenName.ifBlank { result.suggestedNames.firstOrNull() ?: "value" }

                // When "Replace all occurrences" is off, replace only the primary expression.
                val rangesToReplace = if (refactoring.replaceAll) {
                    result.occurrenceRanges.sortedByDescending { it.startOffset }
                } else {
                    listOf(result.expressionRange).sortedByDescending { it.startOffset }
                }

                // Replace occurrences back-to-front so earlier offsets stay valid.
                var newText = originalText
                for (range in rangesToReplace) {
                    newText = newText.substring(0, range.startOffset) +
                            chosenName +
                            newText.substring(range.endOffset)
                }

                // Build declaration keyword and optional type annotation.
                val keyword = if (refactoring.useVar) "var" else "val"
                val typeAnnotation = if (refactoring.explicitType && result.typeText != null) {
                    ": ${result.typeText}"
                } else ""

                // Insert `val/var name[: Type] = expr` before the anchor's line, preserving indentation.
                val lineStart = newText.lastIndexOf('\n', result.anchorOffset - 1) + 1
                val indentation = newText.substring(lineStart, minOf(result.anchorOffset, newText.length))
                    .takeWhile { it == ' ' || it == '\t' }
                val declaration = "$indentation$keyword $chosenName$typeAnnotation = ${result.expressionText}\n"
                newText = newText.substring(0, lineStart) + declaration + newText.substring(lineStart)

                // Write the new text atomically and reformat the inserted declaration range.
                val declEnd = lineStart + declaration.length
                // Caret target: chosenName at the original expression site (trigger location).
                // Shift caused by replacements at offsets lower than the expression range.
                val exprStart = result.expressionRange.startOffset
                val lowerShift = rangesToReplace
                    .filter { it.endOffset <= exprStart }
                    .sumOf { chosenName.length - (it.endOffset - it.startOffset) }
                // Declaration is inserted at lineStart which is before the expression; shift by its length.
                val nameOffset = exprStart + lowerShift + declaration.length
                val atomicDoc = doc as? org.netbeans.editor.AtomicLockDocument
                val body: () -> Unit = {
                    if (doc.length > 0) doc.remove(0, doc.length)
                    doc.insertString(0, newText, null)
                    val end = minOf(declEnd, doc.length)
                    if (lineStart < end) runCatching {
                        format(doc = doc, offset = lineStart, startOffset = lineStart, endOffset = end, proj = nbProject)
                    }
                }
                if (atomicDoc != null) {
                    atomicDoc.atomicLock()
                    try { body() } finally { atomicDoc.atomicUnlock() }
                } else {
                    NbDocument.runAtomicAsUser(doc) { body() }
                }

                // Move caret to the variable name (mirrors IDEA's in-place rename start position).
                SwingUtilities.invokeLater {
                    runCatching { moveCaretToOffset(doc, minOf(nameOffset, doc.length)) }
                }

                KotlinAnalysisAPISession.invalidate(nbProject)
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
            // Restore caret to the position where the user triggered the refactoring.
            // The full-text remove+insert above leaves the caret at document end, so set it
            // directly on the opened editor pane (line.show does not reliably move the caret
            // in an unfocused pane). Double-deferred so it runs after any caret repositioning
            // the undo framework performs when it reverses its own edits.
            val target = minOf(refactoring.startOffset, doc.length)
            SwingUtilities.invokeLater {
                SwingUtilities.invokeLater { restoreCaret(fo, target) }
            }
        }
    }

    /** Sets the caret of the file's opened editor pane to [offset], clamped to the document. */
    private fun restoreCaret(fo: FileObject, offset: Int) {
        runCatching {
            val ec = DataObject.find(fo).lookup.lookup(EditorCookie::class.java) ?: return
            val pane = ec.openedPanes?.firstOrNull() ?: return
            pane.caretPosition = offset.coerceIn(0, pane.document.length)
        }
    }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }
}
