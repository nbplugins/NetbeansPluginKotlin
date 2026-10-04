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

import io.github.nbplugins.kotlin.nbm.navigation.KotlinFindUsagesResultElement
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaMoveNestedMemberComputer
import org.jetbrains.kotlin.log.KotlinLogger
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
import java.io.File
import javax.swing.text.Position.Bias

/** Integrates the safe K2 Move Nested Member subset with NetBeans preview, apply, and undo. */
class KotlinMoveNestedMemberPlugin(
    private val refactoring: KotlinMoveNestedMemberRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {
    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /** Re-resolves the selection and adds preview/apply elements only after a conflict-free validation. */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val source = ProjectUtils.getFileObjectForDocument(refactoring.document)
            ?: return Problem(true, "Move Nested Member requires a saved Kotlin source file.")
        val project = ProjectUtils.getKotlinProjectForFileObject(source)
            ?: ProjectUtils.getValidProject()
            ?: return Problem(true, "Move Nested Member could not resolve the Kotlin project.")
        val request = refactoring.request()
            ?: return Problem(true, "Select a target class or object.")
        val target = fileObject(request.filePath)
            ?: return Problem(true, "Move Nested Member could not resolve the target Kotlin file.")

        KotlinAnalysisAPISession.invalidate(project)
        val session = KotlinAnalysisAPISession.getSession(project)
        val sourcePsi = session.getKtFileForPath(source.path)
            ?: return Problem(true, "Move Nested Member could not resolve the Kotlin source PSI.")
        val targetPsi = session.getKtFileForPath(target.path)
            ?: return Problem(true, "Move Nested Member could not resolve the target Kotlin PSI.")
        val computer = KaMoveNestedMemberComputer(sourcePsi, refactoring.caretOffset)
        return when (val ready = computer.compute()) {
            KaMoveNestedMemberComputer.Outcome.NotApplicable ->
                Problem(true, "Move Nested Member is no longer applicable at this caret location.")
            is KaMoveNestedMemberComputer.Outcome.Error -> Problem(true, ready.error.message ?: "Move Nested Member failed.")
            is KaMoveNestedMemberComputer.Outcome.Ready -> when (val conflicts = computer.checkConflicts(targetPsi, request.offset)) {
                KaMoveNestedMemberComputer.ConflictCheck.Clear -> {
                    runCatching {
                        bag.add(
                            refactoring,
                            KotlinFindUsagesResultElement(
                                OffsetRange(ready.declarationRange.startOffset, ready.declarationRange.endOffset),
                                source,
                            ),
                        )
                    }
                    bag.add(refactoring, KotlinMoveNestedMemberApplyElement(source, project, refactoring))
                    null
                }
                is KaMoveNestedMemberComputer.ConflictCheck.Conflicts -> Problem(true, conflicts.messages.joinToString("\n"))
                KaMoveNestedMemberComputer.ConflictCheck.NotApplicable ->
                    Problem(true, "Move Nested Member target is no longer available.")
                is KaMoveNestedMemberComputer.ConflictCheck.Error ->
                    Problem(true, conflicts.error.message ?: "Move Nested Member validation failed.")
            }
        }
    }

    /** Resolves an absolute K2 virtual-file path to the matching NetBeans file object. */
    private fun fileObject(path: String): FileObject? =
        FileUtil.toFileObject(FileUtil.normalizeFile(File(path)))
}

/** Applies all K2-mutated file texts through one atomic transaction and retains it for undo. */
private class KotlinMoveNestedMemberApplyElement(
    private val sourceFile: FileObject,
    private val project: org.netbeans.api.project.Project,
    private val refactoring: KotlinMoveNestedMemberRefactoring,
) : SimpleRefactoringElementImplementation() {
    private var transaction: KotlinRefactoringTransaction? = null

    override fun getText(): String = "Move nested member"
    override fun getDisplayText(): String = text
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)
    override fun getParentFile(): FileObject = sourceFile
    override fun getPosition(): PositionBounds? = try {
        val support = org.openide.loaders.DataObject.find(sourceFile)
            .lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        PositionBounds(support.createPositionRef(0, Bias.Forward), support.createPositionRef(0, Bias.Backward))
    } catch (_: Exception) { null }

    /** Refreshes K2 PSI, stages every returned final text, and commits them atomically. */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        runCatching {
            val request = refactoring.request() ?: error("Move Nested Member target is incomplete.")
            val target = fileObject(request.filePath) ?: error("Move Nested Member could not resolve target file.")
            KotlinAnalysisAPISession.invalidate(project)
            val session = KotlinAnalysisAPISession.getSession(project)
            val sourcePsi = session.getKtFileForPath(sourceFile.path)
                ?: error("Move Nested Member could not refresh source Kotlin PSI.")
            val targetPsi = session.getKtFileForPath(target.path)
                ?: error("Move Nested Member could not refresh target Kotlin PSI.")
            val current = KotlinRefactoringTransaction()
            pending = current
            current.captureExisting(sourceFile)
            if (target != sourceFile) current.captureExisting(target)

            when (val result = KaMoveNestedMemberComputer(sourcePsi, refactoring.caretOffset).move(targetPsi, request.offset)) {
                is KaMoveNestedMemberComputer.Apply.Success -> {
                    result.changedFiles.forEach { (path, text) ->
                        val changed = fileObject(path)
                            ?: error("Move Nested Member could not resolve changed file $path.")
                        current.captureExisting(changed)
                        current.stageHunkText(changed, text, project)
                    }
                    current.commit()
                    transaction = current
                    pending = null
                }
                is KaMoveNestedMemberComputer.Apply.Conflicts ->
                    error(result.messages.joinToString("\n"))
                KaMoveNestedMemberComputer.Apply.NotApplicable ->
                    error("Move Nested Member is no longer applicable.")
                is KaMoveNestedMemberComputer.Apply.Error -> throw result.error
            }
        }.onFailure { KotlinLogger.INSTANCE.logException("Move Nested Member apply failed", it) }
        runCatching { pending?.rollback() }
            .onFailure { KotlinLogger.INSTANCE.logException("Move Nested Member rollback failed", it) }
        KotlinAnalysisAPISession.invalidate(project)
    }

    /** Restores every transaction participant for NetBeans Undo Last Refactoring. */
    override fun undoChange() {
        runCatching {
            transaction?.undo()
            transaction = null
            KotlinAnalysisAPISession.invalidate(project)
        }.onFailure { KotlinLogger.INSTANCE.logException("Undo Move Nested Member failed", it) }
    }

    /** Resolves a session virtual-file path to NetBeans persistence infrastructure. */
    private fun fileObject(path: String): FileObject? =
        FileUtil.toFileObject(FileUtil.normalizeFile(File(path)))
}
