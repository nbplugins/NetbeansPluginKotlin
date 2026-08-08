/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaIntroduceParameterComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
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
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that implements the Kotlin **Introduce Parameter** refactoring (E9.13,
 * Ctrl+Alt+P).
 *
 * `prepare()` validates the selected expression via [KaIntroduceParameterComputer.compute] and
 * populates [bag] with one [KotlinFindUsagesResultElement] for the selection (preview pane) plus a
 * single [KotlinIntroduceParameterApplyElement]. No dry-run conflict check runs here — same
 * convention as [KotlinChangeSignaturePlugin]: [KaIntroduceParameterComputer.apply] checks conflicts
 * once, right after usage search, and the apply element skips the mutation (logging the conflict
 * messages) if any are found.
 *
 * @param refactoring the carrier [KotlinIntroduceParameterRefactoring]
 */
class KotlinIntroduceParameterPlugin(
    private val refactoring: KotlinIntroduceParameterRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the selected expression and populates [bag] with a preview element plus the
     * [KotlinIntroduceParameterApplyElement] that performs the actual transformation.
     *
     * @return a fatal [Problem] if analysis failed with an exception, `null` otherwise (including
     *   when the selection is simply not applicable — same silent-skip convention as every other
     *   E9.x `RefactoringPlugin`)
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaIntroduceParameterComputer(
            ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
        )
        return when (val outcome = computer.compute()) {
            is KaIntroduceParameterComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinIntroduceParameterPlugin.prepare: NotApplicable at offsets " +
                            "${refactoring.startOffset}..${refactoring.endOffset} in ${fo.path}"
                )
                null
            }
            is KaIntroduceParameterComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException("KotlinIntroduceParameterPlugin.prepare: Error", outcome.error)
                Problem(true, outcome.error.message ?: "Introduce Parameter failed")
            }
            is KaIntroduceParameterComputer.Outcome.Ready -> {
                val result = outcome.result
                runCatching {
                    bag.add(
                        refactoring,
                        KotlinFindUsagesResultElement(
                            org.netbeans.modules.csl.api.OffsetRange(
                                result.selectionRange.startOffset, result.selectionRange.endOffset,
                            ),
                            fo,
                        ),
                    )
                }
                bag.add(refactoring, KotlinIntroduceParameterApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that adds the new parameter, updates every call
 * site, and replaces the chosen occurrences in the body.
 *
 * [KaIntroduceParameterComputer.apply] performs the entire in-memory PSI rewrite in one call and
 * returns a path→text map. Every touched NetBeans document is captured before the hunk-only writes
 * are staged through [KotlinRefactoringTransaction], so a failed participant restores every earlier
 * declaration/caller change exactly while unrelated text remains untouched.
 *
 * @param callerFile the file the caret/selection was actually in (used to re-resolve the expression)
 * @param nbProject  the NetBeans project
 * @param refactoring the carrier holding the user-edited [io.github.nbplugins.kotlin.refactoring.KaIntroduceParameterRequest]
 */
class KotlinIntroduceParameterApplyElement(
    private val callerFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinIntroduceParameterRefactoring,
) : SimpleRefactoringElementImplementation() {

    override fun getText(): String = "Introduce parameter"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(callerFile)
    override fun getParentFile(): FileObject = callerFile

    override fun getPosition(): PositionBounds? = try {
        val dob = DataObject.find(callerFile)
        val ces = dob.lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        val start = ces.createPositionRef(0, Bias.Forward)
        val end = ces.createPositionRef(0, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    /** Successfully committed transaction, used to restore every touched document on undo. */
    private var transaction: KotlinRefactoringTransaction? = null

    /** Applies the K2 result atomically while preserving hunk-only formatting. */
    override fun performChange() {
        var pendingTransaction: KotlinRefactoringTransaction? = null
        runCatching {
            val request = refactoring.request ?: return@runCatching
            val session = KotlinAnalysisAPISession.getSession(nbProject)
            val callerKtFile = session.getKtFileForPath(callerFile.path) ?: return@runCatching
            val outcome = KaIntroduceParameterComputer(
                callerKtFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
            ).apply(request)
            if (outcome is KaIntroduceParameterComputer.ApplyOutcome.Success) {
                val currentTransaction = KotlinRefactoringTransaction()
                pendingTransaction = currentTransaction
                outcome.fileTexts.forEach { (path, text) ->
                    val file = FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path)))
                        ?: error("Introduce Parameter could not resolve changed file $path.")
                    currentTransaction.captureExisting(file)
                    currentTransaction.stageHunkText(file, text, nbProject)
                }
                currentTransaction.commit()
                transaction = currentTransaction
                pendingTransaction = null
            } else if (outcome is KaIntroduceParameterComputer.ApplyOutcome.Conflicts) {
                KotlinLogger.INSTANCE.logWarning("KotlinIntroduceParameterApplyElement: change skipped, conflicts found: ${outcome.messages}")
            } else if (outcome is KaIntroduceParameterComputer.ApplyOutcome.Error) {
                throw outcome.error
            }
        }.onFailure { KotlinLogger.INSTANCE.logException("KotlinIntroduceParameterApplyElement.performChange failed", it) }
        runCatching { pendingTransaction?.rollback() }
            .onFailure { KotlinLogger.INSTANCE.logException("KotlinIntroduceParameterApplyElement rollback failed", it) }
        KotlinAnalysisAPISession.invalidate(nbProject)
    }

    /** Restores every touched file exactly as it was before the committed refactoring. */
    override fun undoChange() {
        runCatching {
            transaction?.undo()
            transaction = null
            KotlinAnalysisAPISession.invalidate(nbProject)
        }.onFailure { KotlinLogger.INSTANCE.logException("KotlinIntroduceParameterApplyElement.undoChange failed", it) }
    }
}
