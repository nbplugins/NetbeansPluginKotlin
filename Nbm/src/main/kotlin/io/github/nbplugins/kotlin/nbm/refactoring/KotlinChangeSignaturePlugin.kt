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

import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureComputer
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureRequest
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
 * [RefactoringPlugin] that implements the Kotlin **Change Signature** refactoring (E9.8, Ctrl+F6).
 *
 * `prepare()` validates the caret is on a function/constructor/class-with-primary-constructor,
 * previews the declaration range, and populates [bag] with a single
 * [KotlinChangeSignatureApplyElement]. No dry-run conflict check runs here (that would need a
 * second full usage search before the dialog even opens); [KaChangeSignatureComputer.apply] instead
 * checks conflicts once, right after usage search, and [KotlinChangeSignatureApplyElement] skips the
 * mutation (logging the conflict messages) if any are found — see [KaChangeSignatureComputer]'s
 * class doc.
 *
 * @param refactoring the carrier [KotlinChangeSignatureRefactoring]
 */
class KotlinChangeSignaturePlugin(
    private val refactoring: KotlinChangeSignatureRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaChangeSignatureComputer(ktFile, refactoring.caretOffset)
        return when (val outcome = computer.compute()) {
            is KaChangeSignatureComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinChangeSignaturePlugin.prepare: NotApplicable at offset " +
                            "${refactoring.caretOffset} in ${fo.path}"
                )
                null
            }
            is KaChangeSignatureComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException("KotlinChangeSignaturePlugin.prepare: Error", outcome.error)
                Problem(true, outcome.error.message ?: "Change Signature failed")
            }
            is KaChangeSignatureComputer.Outcome.Ready -> {
                bag.add(refactoring, KotlinChangeSignatureApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that applies the signature change across every
 * affected file.
 *
 * [KaChangeSignatureComputer.apply] performs the entire in-memory PSI rewrite in one call and
 * returns a path→text map. This element captures every live NetBeans document before changing any
 * of them, stages minimal independently formatted hunks through [KotlinRefactoringTransaction],
 * and then commits all participants. A missing or failing document rolls all earlier writes back;
 * [undoChange] restores every committed participant from the same exact snapshots. A single trailing
 * [KotlinAnalysisAPISession.invalidate] refreshes the session after commit, rollback, or undo.
 *
 * `Redo Last Refactoring` after an undo re-runs [performChange] from scratch (NetBeans' refactoring
 * framework calls it again), so no separate redo bookkeeping is needed.
 *
 * @param callerFile   the file the caret was actually in (used to re-resolve the declaration)
 * @param nbProject    the NetBeans project
 * @param refactoring  the carrier holding the user-edited [KaChangeSignatureRequest]
 */
class KotlinChangeSignatureApplyElement(
    private val callerFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinChangeSignatureRefactoring,
) : SimpleRefactoringElementImplementation() {

    override fun getText(): String = "Change signature"
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

    override fun performChange() {
        var pendingTransaction: KotlinRefactoringTransaction? = null
        runCatching {
            val request = refactoring.request ?: return@runCatching
            val session = KotlinAnalysisAPISession.getSession(nbProject)
            val callerKtFile = session.getKtFileForPath(callerFile.path) ?: return@runCatching
            val outcome = KaChangeSignatureComputer(callerKtFile, refactoring.caretOffset).apply(request)
            if (outcome is KaChangeSignatureComputer.ApplyOutcome.Success) {
                val currentTransaction = KotlinRefactoringTransaction()
                pendingTransaction = currentTransaction
                outcome.fileTexts.forEach { (path, text) ->
                    val file = FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path)))
                        ?: error("Change Signature could not resolve changed file $path.")
                    currentTransaction.captureExisting(file)
                    currentTransaction.stageHunkText(file, text, nbProject)
                }
                currentTransaction.commit()
                transaction = currentTransaction
                pendingTransaction = null
            } else if (outcome is KaChangeSignatureComputer.ApplyOutcome.Conflicts) {
                KotlinLogger.INSTANCE.logWarning("KotlinChangeSignatureApplyElement: change skipped, conflicts found: ${outcome.messages}")
            } else if (outcome is KaChangeSignatureComputer.ApplyOutcome.Error) {
                throw outcome.error
            }
        }.onFailure { KotlinLogger.INSTANCE.logException("KotlinChangeSignatureApplyElement.performChange failed", it) }
        runCatching { pendingTransaction?.rollback() }
            .onFailure { KotlinLogger.INSTANCE.logException("KotlinChangeSignatureApplyElement rollback failed", it) }
        KotlinAnalysisAPISession.invalidate(nbProject)
    }

    /** Restores every touched document exactly as it was before the committed refactoring. */
    override fun undoChange() {
        runCatching {
            transaction?.undo()
            transaction = null
            KotlinAnalysisAPISession.invalidate(nbProject)
        }.onFailure { KotlinLogger.INSTANCE.logException("KotlinChangeSignatureApplyElement.undoChange failed", it) }
    }
}
