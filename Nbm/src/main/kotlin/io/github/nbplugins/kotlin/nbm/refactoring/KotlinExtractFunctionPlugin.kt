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

import io.github.nbplugins.kotlin.nbm.navigation.moveCaretToOffset
import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.GenerateOutcome
import io.github.nbplugins.kotlin.refactoring.KaExtractFunctionComputer
import org.jetbrains.kotlin.utils.ProjectUtils
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
import javax.swing.text.BadLocationException
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that implements the Kotlin **Extract Function** refactoring.
 *
 * Bridges the NetBeans refactoring framework to [KaExtractFunctionComputer]:
 *  1. `prepare()` resolves the selected code and calls the computer to determine parameters,
 *     return type, and suggested names.
 *  2. The user provides the function name in [KotlinExtractFunctionUI]; the apply element reads
 *     the chosen name from [KotlinExtractFunctionRefactoring.chosenName].
 *  3. On apply, the selection is replaced with the generated function call and the new function
 *     definition is inserted before the containing declaration.
 *
 * @param refactoring the carrier [KotlinExtractFunctionRefactoring]
 */
class KotlinExtractFunctionPlugin(
    private val refactoring: KotlinExtractFunctionRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the selected code and populates [bag] with a single
     * [KotlinExtractFunctionApplyElement] that performs the text transformation.
     *
     * @return a fatal [Problem] if the selection is not extractable, `null` otherwise
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val targetSiblingOffset = refactoring.scopeCandidates
            .getOrNull(refactoring.chosenScopeIndex)?.targetSiblingOffset
        val computer = KaExtractFunctionComputer(
            ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
        )
        return when (val outcome = computer.compute(targetSiblingOffset)) {
            is KaExtractFunctionComputer.Outcome.NotApplicable ->
                Problem(true, "No extractable code at the selection.")
            is KaExtractFunctionComputer.Outcome.Error ->
                Problem(true, outcome.error.message ?: "Extract Function analysis failed")
            is KaExtractFunctionComputer.Outcome.Ready -> {
                bag.add(refactoring, KotlinExtractFunctionApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing element that performs the extract-function text transformation.
 *
 * Strategy:
 *  1. Re-runs [KaExtractFunctionComputer.generate] to get a fresh result against the current K2
 *     session — this drives IDEA's real K2 generation engine
 *     (`Generator.generateDeclaration`), which mutates the session's live `KtFile` PSI directly
 *     (inserts the new declaration, rewrites the call site, shortens references, reformats via
 *     `CodeStyleManager`) rather than building text by hand.
 *  2. Reduces that PSI mutation to a single [com.intellij.openapi.util.TextRange] + replacement
 *     text (see [io.github.nbplugins.kotlin.refactoring.KaExtractFunctionEdit]) and applies it as
 *     a **minimal, targeted document edit** (never a whole-document replace), reformats the
 *     affected range, and invalidates the K2 session so the next analysis reflects the change.
 *
 * A caret-restore edit is joined to the atomic undo group so a native Ctrl+Z keeps the caret at the
 * trigger site instead of at EOF. [undoChange] is a snapshot-based fallback for non-editor undo paths.
 *
 * @param declarationFile the file containing the selection
 * @param nbProject       the NetBeans project (for K2 session access and invalidation)
 * @param refactoring     the carrier holding [KotlinExtractFunctionRefactoring.chosenName]
 */
class KotlinExtractFunctionApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinExtractFunctionRefactoring,
) : SimpleRefactoringElementImplementation() {

    private var snapshot: String? = null

    override fun getText(): String = "Extract function"
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

                val targetSiblingOffset = refactoring.scopeCandidates
                    .getOrNull(refactoring.chosenScopeIndex)?.targetSiblingOffset
                val computer = KaExtractFunctionComputer(
                    ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
                )
                val chosenName = refactoring.chosenName.ifBlank { "extractedFunction" }
                val ready = computer.generate(chosenName, targetSiblingOffset) as? GenerateOutcome.Ready
                    ?: return@runCatching
                val edit = ready.edit

                val doc = openDocument(fo) ?: return@runCatching
                val originalText = doc.getText(0, doc.length)
                snapshot = originalText

                // Apply the engine's mutation as a single minimal, targeted document edit and join
                // a caret-restore edit so a native Ctrl+Z keeps the caret at the trigger site
                // instead of at EOF (see joinCaretRestoreOnUndo).
                val atomicDoc = doc as? org.netbeans.editor.AtomicLockDocument
                val caretTargetOnUndo = minOf(refactoring.startOffset, originalText.length)
                val body: () -> Unit = {
                    joinCaretRestoreOnUndo(doc, fo, caretTargetOnUndo)
                    MinimalDocumentEdits.apply(doc, listOf(edit.changedRange), edit.replacementText, null, null)
                    val start = edit.changedRange.startOffset
                    // formatEndOffset (not just start + replacementText.length) guarantees the
                    // inserted declaration's own closing brace is included even when the cheap
                    // diff under-counted the changed region — see KaExtractFunctionEdit's KDoc.
                    val end = minOf(edit.formatEndOffset, doc.length)
                    if (start < end) runCatching {
                        format(doc = doc, offset = start, startOffset = start, endOffset = end, proj = nbProject)
                    }
                }
                if (atomicDoc != null) {
                    atomicDoc.atomicLock()
                    try { body() } finally { atomicDoc.atomicUnlock() }
                } else {
                    NbDocument.runAtomicAsUser(doc) { body() }
                }

                // Move caret to the call site (mirrors IDEA's in-place rename start position).
                // edit.caretOffset is already in post-edit document coordinates, since the single
                // MinimalDocumentEdits.apply above reproduces the engine's post-mutation text exactly.
                SwingUtilities.invokeLater {
                    runCatching { moveCaretToOffset(doc, minOf(edit.caretOffset, doc.length)) }
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
        }
    }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }
}
