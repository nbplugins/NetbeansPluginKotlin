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

import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
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
 *  1. Re-runs [KaExtractFunctionComputer] to get a fresh result against the current K2 session.
 *  2. Builds the extracted function text from the descriptor's parameter list, return type, and
 *     the original selection text (used as the function body).
 *  3. Inserts the new function definition before the anchor offset (before the containing decl).
 *  4. Replaces the original selection with a call expression.
 *  5. Writes back atomically via [NbDocument.runAtomicAsUser] and reformats the inserted range.
 *  6. Invalidates the K2 session so subsequent analyses see the updated source.
 *
 * Undo restores the pre-refactor snapshot in a single step.
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
                val ready = computer.compute(targetSiblingOffset) as? KaExtractFunctionComputer.Outcome.Ready
                    ?: return@runCatching
                val result = ready.result

                val doc = openDocument(fo) ?: return@runCatching
                val originalText = doc.getText(0, doc.length)
                snapshot = originalText

                val chosenName = refactoring.chosenName.ifBlank {
                    result.suggestedNames.firstOrNull() ?: "extractedFunction"
                }

                // Build the function signature: "fun name(p1: T1, p2: T2): ReturnType"
                val paramList = result.parameters.joinToString(", ") { "${it.name}: ${it.typeText}" }
                val returnClause = if (result.returnTypeText != null) ": ${result.returnTypeText}" else ""
                val callArgs = result.parameters.joinToString(", ") { it.name }

                // Indentation of the anchor line (where the new function is inserted)
                val insertLineStart = originalText.lastIndexOf('\n', result.insertOffset - 1) + 1
                val indent = originalText.substring(insertLineStart, minOf(result.insertOffset, originalText.length))
                    .takeWhile { it == ' ' || it == '\t' }

                // Build the function body using the original selection text
                val bodyIndent = "$indent    "
                val bodyLines = result.selectionText.trimEnd().lines()
                    .joinToString("\n") { "$bodyIndent$it" }
                val functionText = "${indent}fun $chosenName($paramList)$returnClause {\n$bodyLines\n$indent}\n\n"

                // Build the call expression that replaces the selection
                val callExpression = "$chosenName($callArgs)"

                // Apply: first replace selection (higher offset) then insert (lower offset) so
                // insert offset stays valid after the selection replacement.
                var newText = originalText
                val selStart = result.selectionRange.startOffset
                val selEnd = result.selectionRange.endOffset

                // 1. Replace selection with call expression
                newText = newText.substring(0, selStart) + callExpression + newText.substring(selEnd)

                // 2. Insert new function before the anchor (insertOffset is now correct because
                //    function insertion happens at a lower offset than the selection replacement
                //    only when the selection is INSIDE the declaration — which is always the case).
                //    If insertOffset > selStart (unusual), adjust for the replacement delta.
                val adjustedInsert = if (result.insertOffset > selStart) {
                    result.insertOffset - (selEnd - selStart) + callExpression.length
                } else {
                    result.insertOffset
                }
                val insertLineStartInNew = newText.lastIndexOf('\n', adjustedInsert - 1) + 1
                newText = newText.substring(0, insertLineStartInNew) + functionText + newText.substring(insertLineStartInNew)

                val insertedEnd = insertLineStartInNew + functionText.length

                val atomicDoc = doc as? org.netbeans.editor.AtomicLockDocument
                val body: () -> Unit = {
                    if (doc.length > 0) doc.remove(0, doc.length)
                    doc.insertString(0, newText, null)
                    val end = minOf(insertedEnd, doc.length)
                    if (insertLineStartInNew < end) runCatching {
                        format(doc = doc, offset = insertLineStartInNew,
                               startOffset = insertLineStartInNew, endOffset = end, proj = nbProject)
                    }
                }
                if (atomicDoc != null) {
                    atomicDoc.atomicLock()
                    try { body() } finally { atomicDoc.atomicUnlock() }
                } else {
                    NbDocument.runAtomicAsUser(doc) { body() }
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
