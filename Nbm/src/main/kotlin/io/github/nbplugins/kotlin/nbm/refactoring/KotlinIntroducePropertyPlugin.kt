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
import io.github.nbplugins.kotlin.refactoring.KaIntroducePropertyComputer
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
 * [RefactoringPlugin] that implements the Kotlin **Introduce Property** refactoring.
 *
 * Bridges the NetBeans refactoring framework to [KaIntroducePropertyComputer]:
 *  1. `prepare()` validates the selected expression and populates [bag] with one occurrence element
 *     and a single [KotlinIntroducePropertyApplyElement] that performs the transformation.
 *  2. A fatal [Problem] is returned if the expression is not suitable for property introduction
 *     (e.g. not inside a class body or file scope, or it captures local variables).
 *
 * @param refactoring the carrier [KotlinIntroducePropertyRefactoring]
 */
class KotlinIntroducePropertyPlugin(
    private val refactoring: KotlinIntroducePropertyRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the selected expression and populates [bag] with:
     *  - one [KotlinFindUsagesResultElement] for the selection (for the preview pane),
     *  - one [KotlinIntroducePropertyApplyElement] that performs the actual transformation.
     *
     * @return a fatal [Problem] if the expression cannot be extracted as a property, `null` otherwise
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaIntroducePropertyComputer(
            ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
        )
        return when (val outcome = computer.compute(refactoring.targetSiblingOffset)) {
            is KaIntroducePropertyComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinIntroducePropertyPlugin.prepare: NotApplicable at offsets " +
                            "${refactoring.startOffset}..${refactoring.endOffset} in ${fo.path}"
                )
                null
            }
            is KaIntroducePropertyComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException(
                    "KotlinIntroducePropertyPlugin.prepare: Error", outcome.error
                )
                Problem(true, outcome.error.message ?: "Introduce Property failed")
            }
            is KaIntroducePropertyComputer.Outcome.Ready -> {
                val result = outcome.result
                runCatching {
                    bag.add(
                        refactoring,
                        KotlinFindUsagesResultElement(
                            OffsetRange(result.selectionRange.startOffset, result.selectionRange.endOffset),
                            fo,
                        ),
                    )
                }
                bag.add(refactoring, KotlinIntroducePropertyApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that performs the introduce-property transformation.
 *
 * Strategy:
 *  1. Re-runs [KaIntroducePropertyComputer] to get a fresh result.
 *  2. Replaces the selected expression with [KotlinIntroducePropertyRefactoring.chosenName].
 *  3. Inserts `val/var NAME: Type = expr` before the target sibling at [insertOffset].
 *  4. Writes the result back to the document and invalidates the K2 session.
 *
 * Undo restores the pre-refactor snapshot in a single step.
 *
 * @param declarationFile  the file containing the expression
 * @param nbProject        the NetBeans project
 * @param refactoring      the carrier holding options chosen by the user
 */
class KotlinIntroducePropertyApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinIntroducePropertyRefactoring,
) : SimpleRefactoringElementImplementation() {

    private var snapshot: String? = null

    override fun getText(): String = "Introduce property"
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

                val computer = KaIntroducePropertyComputer(
                    ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
                )
                val outcome = computer.compute(refactoring.targetSiblingOffset)
                val ready = outcome as? KaIntroducePropertyComputer.Outcome.Ready ?: return@runCatching
                val result = ready.result

                val doc = openDocument(fo) ?: return@runCatching
                val originalText = doc.getText(0, doc.length)
                snapshot = originalText

                val chosenName = refactoring.chosenName.ifBlank {
                    result.suggestedNames.firstOrNull() ?: "myProperty"
                }
                val keyword = if (refactoring.useVar) "var" else "val"
                val typeAnnotation = result.returnTypeText?.let { ": $it" } ?: ""
                val propertyDeclaration = "$keyword $chosenName$typeAnnotation = ${result.selectionText}"

                // Replace the selected expression with the property name.
                val range = result.selectionRange
                var newText = originalText.substring(0, range.startOffset) +
                        chosenName +
                        originalText.substring(range.endOffset)

                // Compute the adjusted insertion offset after the replacement.
                val shift = chosenName.length - (range.endOffset - range.startOffset)
                val adjustedInsert = result.insertOffset + if (result.insertOffset > range.startOffset) shift else 0

                // Insert the property declaration before the target sibling.
                val lineStart = newText.lastIndexOf('\n', adjustedInsert - 1) + 1
                val indentation = newText.substring(lineStart, minOf(adjustedInsert, newText.length))
                    .takeWhile { it == ' ' || it == '\t' }
                val insertedText = "$indentation$propertyDeclaration\n"
                newText = newText.substring(0, lineStart) + insertedText + newText.substring(lineStart)

                val atomicDoc = doc as? org.netbeans.editor.AtomicLockDocument
                val body: () -> Unit = {
                    if (doc.length > 0) doc.remove(0, doc.length)
                    doc.insertString(0, newText, null)
                }
                if (atomicDoc != null) {
                    atomicDoc.atomicLock()
                    try { body() } finally { atomicDoc.atomicUnlock() }
                } else {
                    NbDocument.runAtomicAsUser(doc) { body() }
                }

                // Move caret to the property name in the usage location.
                val usageNameOffset = range.startOffset +
                        if (lineStart <= range.startOffset) insertedText.length else 0
                SwingUtilities.invokeLater {
                    runCatching { moveCaretToOffset(doc, minOf(usageNameOffset, doc.length)) }
                }

                KotlinAnalysisAPISession.invalidate(nbProject)
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException("KotlinIntroducePropertyApplyElement.performChange failed", e)
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
