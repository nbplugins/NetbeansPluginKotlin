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
import io.github.nbplugins.kotlin.refactoring.KaMoveDeclarationComputer
import io.github.nbplugins.kotlin.refactoring.KaMoveFileComputer
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
 * Bridges NetBeans Move Kotlin File and Move Kotlin Directory UI to the standalone K2 semantic
 * adapter.
 *
 * NetBeans owns destination creation, physical movement, document persistence, rollback, and undo.
 * The ported K2 handler supplies eligible package rewriting, Kotlin conflict detection, and
 * retargeting of supported external Kotlin references.
 *
 * @param refactoring carrier containing selected Kotlin sources and the destination selected in UI
 */
class KotlinMoveFilePlugin(
    private val refactoring: KotlinMoveFileRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    /** @return no preliminary problem; source validation happens while preparing the operation. */
    override fun preCheck(): Problem? = null

    /** @return no fast problem; the UI validates the package before this point. */
    override fun fastCheckParameters(): Problem? = null

    /** @return no extra parameter problem. */
    override fun checkParameters(): Problem? = null

    /** Cancels no external work because all Move File work runs in the refactoring lifecycle. */
    override fun cancelRequest() = Unit

    /**
     * Adds a preview element for every selected Kotlin source and one atomic apply element.
     *
     * @param bag NetBeans bag populated with preview and mutation elements
     * @return a fatal problem when a source cannot be analyzed, otherwise `null`
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        KotlinLogger.INSTANCE.logInfo("KotlinMoveFilePlugin.prepare: plugin created")
        val selection = refactoring.selection
        val sourceProject = ProjectUtils.getKotlinProjectForFileObject(selection.representativeFile)
            ?: ProjectUtils.getValidProject()
            ?: return Problem(true, "Move Kotlin File could not find the source project.")
        KotlinLogger.INSTANCE.logInfo(
            "KotlinMoveFilePlugin.prepare: ${selection.sourceFiles.size} Kotlin source(s), " +
                "project=${sourceProject.projectDirectory.path}",
        )
        val session = KotlinAnalysisAPISession.getSession(sourceProject)
        val sources = selection.sourceFiles.map { source ->
            session.getKtFileForPath(source.path)
                ?: return Problem(true, "Move Kotlin File could not resolve ${source.nameExt}.")
        }
        val problem = sources.firstNotNullOfOrNull { file ->
            when (val outcome = KaMoveFileComputer(file).compute()) {
                is KaMoveFileComputer.Outcome.NotApplicable -> Problem(true, "The selected Kotlin file has no physical path.")
                is KaMoveFileComputer.Outcome.Error -> Problem(true, outcome.error.message ?: "Move Kotlin File analysis failed.")
                is KaMoveFileComputer.Outcome.Ready -> null
            }
        }
        if (problem != null) return problem
        sources.zip(selection.sourceFiles).forEach { (file, source) ->
            bag.add(refactoring, KotlinFindUsagesResultElement(OffsetRange(0, file.textLength), source))
        }
        bag.add(refactoring, KotlinMoveFileApplyElement(selection, sourceProject, refactoring))
        return null
    }
}

/**
 * Performs one atomic Kotlin file or directory move and retains the transaction for Undo Last
 * Refactoring.
 *
 * @param selection Kotlin sources selected by the editor or directory-node action
 * @param project NetBeans project owning the selected sources
 * @param refactoring destination and search parameters
 */
class KotlinMoveFileApplyElement(
    private val selection: KotlinMoveSourceSelection,
    private val project: org.netbeans.api.project.Project,
    private val refactoring: KotlinMoveFileRefactoring,
) : SimpleRefactoringElementImplementation() {

    /** Successful transaction retained so [undoChange] can restore original paths and text. */
    private var transaction: KotlinRefactoringTransaction? = null

    /** @return user-visible preview text. */
    override fun getText(): String = if (selection.isDirectory) "Move Kotlin directory" else "Move Kotlin file"

    /** @return user-visible preview text. */
    override fun getDisplayText(): String = getText()

    /** @return selected representative file lookup. */
    override fun getLookup(): Lookup = Lookups.fixed(selection.representativeFile)

    /** @return representative source file for NetBeans preview placement. */
    override fun getParentFile(): FileObject = selection.representativeFile

    /** @return source position bounds, when the editor support is available. */
    override fun getPosition(): PositionBounds? = try {
        val support = org.openide.loaders.DataObject.find(selection.representativeFile)
            .lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        PositionBounds(
            support.createPositionRef(0, Bias.Forward),
            support.createPositionRef(0, Bias.Backward),
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Runs K2 semantics first, then atomically moves every physical Kotlin file and persists every
     * changed Kotlin document. Any failure rolls paths, documents, owned destination folders, and
     * created files back to their original state.
     */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        runCatching {
            val packageTarget = KotlinPackageTarget(project, selection.representativeFile)
            val targetRoot = packageTarget.roots.firstOrNull { it.path == refactoring.targetRootPath }
                ?: packageTarget.roots.firstOrNull { it.path == packageTarget.defaultRootPath }
                ?: error("Move Kotlin File could not find a destination source root.")
            val targetPackage = refactoring.targetPackage.trim()
            check(packageTarget.isValidPackage(targetPackage)) { "Target package is invalid: $targetPackage" }

            val current = KotlinRefactoringTransaction()
            pending = current
            val sourceByPath = selection.sourceFiles.associateBy(FileObject::getPath)
            val targetFolders = selection.sourceFiles.associateWith { source ->
                val packageSegments = targetPackage.split('.').filter(String::isNotEmpty) +
                    selection.targetRelativeFolderSegments(source)
                createTargetFolder(current, targetRoot.folder, packageSegments)
            }
            validatePhysicalTargets(selection.sourceFiles, targetFolders)

            val session = KotlinAnalysisAPISession.getSession(project)
            val ktTargets = selection.sourceFiles.associate { source ->
                val sourceKtFile = session.getKtFileForPath(source.path)
                    ?: error("Move Kotlin File could not resolve writable source PSI: ${source.nameExt}.")
                val targetFolder = targetFolders.getValue(source)
                val targetDirectory = KaMoveDeclarationComputer.resolveDirectory(sourceKtFile.project, targetFolder.path)
                    ?: error("Move Kotlin File could not resolve destination PSI directory: ${targetFolder.path}.")
                val finalPackage = targetPackageFor(source, targetPackage)
                sourceKtFile to KaMoveFileComputer.MoveTarget(targetDirectory, FqName(finalPackage))
            }
            when (val outcome = KaMoveFileComputer(ktTargets.keys.first()).apply(ktTargets, refactoring.updateReferences)) {
                is KaMoveFileComputer.ApplyOutcome.Conflicts -> throw IllegalStateException(outcome.messages.joinToString("\n"))
                is KaMoveFileComputer.ApplyOutcome.Error -> throw outcome.error
                is KaMoveFileComputer.ApplyOutcome.Success -> {
                    selection.sourceFiles.forEach { source -> current.moveFile(source, targetFolders.getValue(source)) }
                    outcome.changedFiles.forEach { (path, text) ->
                        val file = sourceByPath[path] ?: FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path)))
                            ?: error("Move Kotlin File could not resolve changed file $path.")
                        current.captureExisting(file, outcome.originalTexts[path])
                        current.stageText(file, text)
                    }
                    current.commit()
                    transaction = current
                    KotlinLogger.INSTANCE.logInfo("KotlinMoveFileApplyElement.performChange: transaction committed")
                    pending = null
                }
            }
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinMoveFileApplyElement.performChange failed", error)
        }
        runCatching { pending?.rollback() }
            .onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinMoveFileApplyElement rollback failed", error) }
        KotlinAnalysisAPISession.invalidate(project)
    }

    /** Restores original source paths and texts through the transaction retained after commit. */
    override fun undoChange() {
        runCatching {
            val current = transaction
            if (current == null) {
                KotlinLogger.INSTANCE.logWarning("KotlinMoveFileApplyElement.undoChange: no retained transaction")
            } else {
                KotlinLogger.INSTANCE.logInfo("KotlinMoveFileApplyElement.undoChange: restoring retained transaction")
                current.undo()
                KotlinLogger.INSTANCE.logInfo("KotlinMoveFileApplyElement.undoChange: transaction restored")
                transaction = null
            }
            KotlinAnalysisAPISession.invalidate(project)
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinMoveFileApplyElement.undoChange failed", error)
        }
    }

    /** Rejects same-directory, duplicate-path, and pre-existing-target moves before K2 mutates PSI. */
    private fun validatePhysicalTargets(
        sources: List<FileObject>,
        targets: Map<FileObject, FileObject>,
    ) {
        val targetPaths = mutableSetOf<String>()
        sources.forEach { source ->
            val target = targets.getValue(source)
            check(target != source.parent) { "${source.nameExt} is already in the selected destination folder." }
            val path = "${target.path}/${source.nameExt}"
            check(targetPaths.add(path)) { "Several selected Kotlin files would move to $path." }
            val existing = target.getFileObject(source.name, source.ext)
            check(existing == null || existing == source) { "Target already contains ${source.nameExt}." }
        }
    }

    /** Computes one source's final package, retaining a selected directory's nested hierarchy. */
    private fun targetPackageFor(source: FileObject, basePackage: String): String {
        val segments = basePackage.split('.').filter(String::isNotEmpty) + selection.targetRelativeFolderSegments(source)
        return segments.joinToString(".")
    }

    /** Creates only destination package folders that this transaction can safely delete on undo. */
    private fun createTargetFolder(
        transaction: KotlinRefactoringTransaction,
        root: FileObject,
        segments: List<String>,
    ): FileObject = segments.fold(root) { parent, segment -> transaction.createFolder(parent, segment) }
}
