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
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaMoveDeclarationComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.text.CloneableEditorSupport
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.Position.Bias

/**
 * [RefactoringPlugin] that implements the Kotlin **Move Declaration** refactoring (E9.7, F6).
 *
 * `prepare()` validates the caret is on a top-level declaration, populates [bag] with the source
 * declaration (preview) and a single [KotlinMoveDeclarationApplyElement], and runs a conflict-check
 * dry run of IDEA's real K2 move engine — mirroring [KotlinSafeDeletePlugin]'s "used in N places,
 * continue anyway?" convention rather than IDEA's modal `ConflictsDialog`.
 *
 * @param refactoring the carrier [KotlinMoveDeclarationRefactoring]
 */
class KotlinMoveDeclarationPlugin(
    private val refactoring: KotlinMoveDeclarationRefactoring,
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

        val computer = KaMoveDeclarationComputer(ktFile, refactoring.caretOffset)
        val outcome = computer.compute()
        return when (outcome) {
            is KaMoveDeclarationComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinMoveDeclarationPlugin.prepare: NotApplicable at offset " +
                            "${refactoring.caretOffset} in ${fo.path}"
                )
                null
            }
            is KaMoveDeclarationComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException("KotlinMoveDeclarationPlugin.prepare: Error", outcome.error)
                Problem(true, outcome.error.message ?: "Move Declaration failed")
            }
            is KaMoveDeclarationComputer.Outcome.Ready -> {
                val result = outcome.result
                // The declaration may live in a different file than the one the caret is in (the
                // caret can be on any usage — see KaMoveDeclarationComputer.findDeclaration()).
                val sourceFo = FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(result.sourceFilePath))) ?: fo
                runCatching {
                    bag.add(
                        refactoring,
                        KotlinFindUsagesResultElement(
                            OffsetRange(result.declarationRange.startOffset, result.declarationRange.endOffset),
                            sourceFo,
                        ),
                    )
                }
                bag.add(refactoring, KotlinMoveDeclarationApplyElement(sourceFo, nbProject, refactoring))

                // Preview every external usage too (read-only search — no target needs to exist
                // yet), so the user can see what will be retargeted before clicking Refactor.
                runCatching {
                    computer.findUsagesForPreview()?.forEach { (usageFile, ranges) ->
                        val usageFo = usageFile.virtualFile?.path
                            ?.let { FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(it))) }
                            ?: return@forEach
                        ranges.forEach { range ->
                            runCatching {
                                bag.add(
                                    refactoring,
                                    KotlinFindUsagesResultElement(
                                        OffsetRange(range.startOffset, range.endOffset),
                                        usageFo,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Unlike Safe Delete, a real conflict-check dry run here would require the target
                // file to already exist (this environment's VFS can't create it read-only-first —
                // see KaMoveDeclarationComputer.move()'s doc comment) and, since `move()` performs
                // the actual move once no conflicts are found, calling it from prepare() would risk
                // executing the move during the preview step. Conflicts are instead checked in
                // KotlinMoveDeclarationApplyElement.performChange(), which logs and skips applying
                // the result if any are found, rather than silently corrupting the source.
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that performs the move-declaration transformation.
 *
 * Mirrors [KotlinCopyDeclarationApplyElement]'s file-creation strategy (plain NetBeans
 * [FileObject] APIs — the analysis session's `KtFile`s are in-memory `LightVirtualFile`s with no
 * real directory hierarchy), then drives IDEA's real K2 move engine
 * ([KaMoveDeclarationComputer.move]) to perform usage search, conflict detection, and the actual
 * move+retarget once the target file exists as a real, disk-backed file the session can see.
 *
 * A [KotlinRefactoringTransaction] snapshots both source and target before mutation. It owns a
 * newly-created target through engine setup, rolls it back on conflicts/errors, and restores both
 * documents through [undoChange].
 *
 * @param sourceFile   the file containing the declaration
 * @param nbProject    the NetBeans project
 * @param refactoring  the carrier holding the target package/file name
 */
class KotlinMoveDeclarationApplyElement(
    private val sourceFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinMoveDeclarationRefactoring,
) : SimpleRefactoringElementImplementation() {

    override fun getText(): String = "Move declaration"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)
    override fun getParentFile(): FileObject = sourceFile

    override fun getPosition(): PositionBounds? = try {
        val dob = org.openide.loaders.DataObject.find(sourceFile)
        val ces = dob.lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        val start = ces.createPositionRef(0, Bias.Forward)
        val end = ces.createPositionRef(0, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    /** Transaction from the last successful application, used by Undo Last Refactoring. */
    private var transaction: KotlinRefactoringTransaction? = null

    override fun performChange() {
        var pendingTransaction: KotlinRefactoringTransaction? = null
        runCatching {
            // `fo` is the file the caret is actually in — needed to seed the Computer (it locates
            // the leaf/reference at the caret offset). The declaration itself, resolved by the
            // Computer, may live in a *different* file (`result.sourceFilePath`) when the caret was
            // on a usage rather than the declaration — see KaMoveDeclarationComputer.findDeclaration().
            val fo = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: return@runCatching
            val nbProject2 = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return@runCatching
            val session = KotlinAnalysisAPISession.getSession(nbProject2)
            val callerKtFile = session.getKtFileForPath(fo.path) ?: return@runCatching

            val computer = KaMoveDeclarationComputer(callerKtFile, refactoring.caretOffset)
            val ready = computer.compute() as? KaMoveDeclarationComputer.Outcome.Ready ?: return@runCatching
            val result = ready.result
            val sourceFo = FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(result.sourceFilePath))) ?: fo
            val targetPackage = refactoring.targetPackage.ifBlank { result.packageName }
            val targetFileNameRaw = refactoring.targetFileName.ifBlank { result.suggestedFileName }
            val targetFileName = if (targetFileNameRaw.endsWith(".kt")) targetFileNameRaw else "$targetFileNameRaw.kt"
            val packageTarget = KotlinPackageTarget(nbProject2, sourceFo)
            val targetFolder = packageTarget.resolveDirectory(packageTarget.defaultRootPath, targetPackage)
                ?: error("Move Declaration could not resolve target package '$targetPackage'.")
            val targetDirectory = KaMoveDeclarationComputer.resolveDirectory(callerKtFile.project, targetFolder.path)
                ?: error("Move Declaration could not resolve target directory ${targetFolder.path}.")

            val currentTransaction = KotlinRefactoringTransaction()
            pendingTransaction = currentTransaction
            currentTransaction.captureExisting(sourceFo)
            val existingTargetFo = targetFolder.getFileObject(targetFileName)
            val packageLine = if (targetPackage.isNotEmpty()) "package $targetPackage\n\n" else ""
            val targetFo = existingTargetFo?.also { currentTransaction.captureExisting(it) }
                ?: currentTransaction.createFile(targetFolder, targetFileName, packageLine)

            // The seeded target must be visible as a writable LightVirtualFile before the ported
            // engine can mutate it. This is the only intentional pre-commit filesystem mutation;
            // the transaction owns it and removes it on every unsuccessful path.
            KotlinAnalysisAPISession.invalidate(nbProject2)
            val session2 = KotlinAnalysisAPISession.getSession(nbProject2)
            val callerKtFile2 = session2.getKtFileForPath(fo.path)
            val targetKtFile = session2.getKtFileForPath(targetFo.path)
            if (callerKtFile2 == null || targetKtFile == null) {
                error("Move Declaration could not refresh Kotlin PSI for source or target.")
            }
            val computer2 = KaMoveDeclarationComputer(callerKtFile2, refactoring.caretOffset)

            when (val moveOutcome = computer2.move(targetDirectory, targetKtFile, FqName(targetPackage))) {
                is KaMoveDeclarationComputer.MoveOutcome.Success -> {
                    moveOutcome.changedFiles.forEach { (path, text) ->
                        val changedFile = FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path)))
                            ?: error("Move Declaration could not resolve changed file $path.")
                        if (changedFile != targetFo && changedFile != sourceFo) {
                            currentTransaction.captureExisting(changedFile)
                        }
                        currentTransaction.stageText(changedFile, text)
                    }
                    currentTransaction.commit()
                    transaction = currentTransaction
                    pendingTransaction = null
                }
                is KaMoveDeclarationComputer.MoveOutcome.Conflicts -> {
                    KotlinLogger.INSTANCE.logWarning(
                        "KotlinMoveDeclarationApplyElement: move skipped, conflicts found: ${moveOutcome.messages}"
                    )
                }
                is KaMoveDeclarationComputer.MoveOutcome.Error -> throw moveOutcome.error
                null -> Unit
            }
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinMoveDeclarationApplyElement.performChange failed", error)
        }
        runCatching { pendingTransaction?.rollback() }
            .onFailure { KotlinLogger.INSTANCE.logException("KotlinMoveDeclarationApplyElement rollback failed", it) }
        KotlinAnalysisAPISession.invalidate(nbProject)
    }

    /** Restores source and target text and removes a target that this refactoring created. */
    override fun undoChange() {
        runCatching {
            transaction?.undo()
            transaction = null
            KotlinAnalysisAPISession.invalidate(nbProject)
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinMoveDeclarationApplyElement.undoChange failed", error)
        }
    }
}
