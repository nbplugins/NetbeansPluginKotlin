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
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.Position.Bias

/**
 * Creates the NetBeans refactoring lifecycle for an explicit Kotlin file rename.
 *
 * @param refactoring physical file rename requested from the editor action
 */
class KotlinRenameFilePlugin(
    private val refactoring: KotlinRenameFileRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    /** @return a fatal problem when the selected source is not a valid Kotlin file. */
    override fun preCheck(): Problem? = validateKotlinFile(refactoring.file)

    /** @return a fatal problem when the requested basename is invalid. */
    override fun fastCheckParameters(): Problem? = validateIdentifier(refactoring.newName)

    /** @return the same validation used before UI confirmation. */
    override fun checkParameters(): Problem? = fastCheckParameters()

    /** Cancels no external work because the operation runs within the lifecycle. */
    override fun cancelRequest() = Unit

    /** Adds one preview and atomic transaction apply element. */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        preCheck()?.let { return it }
        fastCheckParameters()?.let { return it }
        bag.add(refactoring, KotlinFindUsagesResultElement(OffsetRange.NONE, refactoring.file))
        bag.add(refactoring, KotlinRenameFileApplyElement(refactoring))
        return null
    }
}

/**
 * Performs and undoes one physical Kotlin-file basename rename.
 *
 * @param refactoring file and target basename selected by the user
 */
class KotlinRenameFileApplyElement(
    private val refactoring: KotlinRenameFileRefactoring,
) : SimpleRefactoringElementImplementation() {
    private var transaction: KotlinRefactoringTransaction? = null

    /** @return preview label. */
    override fun getText(): String = "Rename Kotlin file"

    /** @return preview label. */
    override fun getDisplayText(): String = text

    /** @return renamed source lookup. */
    override fun getLookup(): Lookup = Lookups.fixed(refactoring.file)

    /** @return renamed physical Kotlin file. */
    override fun getParentFile(): FileObject = refactoring.file

    /** @return source bounds when an editor support is available. */
    override fun getPosition(): PositionBounds? = positionFor(refactoring.file)

    /** Renames the basename atomically and retains the completed transaction for undo. */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        try {
            val current = KotlinRefactoringTransaction()
            pending = current
            current.renameFile(refactoring.file, refactoring.newName, refactoring.file.ext)
            current.commit()
            transaction = current
            pending = null
        } catch (error: Throwable) {
            KotlinLogger.INSTANCE.logException("KotlinRenameFileApplyElement.performChange failed", error)
            rollbackAfterFailure(pending, error, "KotlinRenameFileApplyElement rollback failed")
            throw error
        }
    }

    /** Restores the original basename. */
    override fun undoChange() {
        runCatching { transaction?.undo(); transaction = null }
            .onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinRenameFileApplyElement.undoChange failed", error) }
    }
}

/**
 * Creates the NetBeans lifecycle for a Kotlin package-directory rename.
 *
 * @param refactoring folder rename and reference-update configuration from the folder action
 */
class KotlinRenameDirectoryPlugin(
    private val refactoring: KotlinRenameDirectoryRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    /** @return a fatal problem when the selected folder has no Kotlin sources. */
    override fun preCheck(): Problem? = if (directorySelection() != null) {
        null
    } else {
        Problem(true, "Rename Kotlin Package Directory requires a folder containing Kotlin files.")
    }

    /** @return a fatal problem when the target name is not a Kotlin identifier. */
    override fun fastCheckParameters(): Problem? = validateIdentifier(refactoring.newName)

    /** @return the same validation used before UI confirmation. */
    override fun checkParameters(): Problem? = fastCheckParameters()

    /** Cancels no external work because the operation runs within the lifecycle. */
    override fun cancelRequest() = Unit

    /** Adds source previews and one atomic physical folder-rename apply element. */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val selection = directorySelection()
            ?: return Problem(true, "Rename Kotlin Package Directory requires a folder containing Kotlin files.")
        fastCheckParameters()?.let { return it }
        val project = ProjectUtils.getKotlinProjectForFileObject(selection.representativeFile)
            ?: ProjectUtils.getValidProject()
            ?: return Problem(true, "Rename Kotlin Package Directory could not find the source project.")
        val session = KotlinAnalysisAPISession.getSession(project)
        selection.sourceFiles.forEach { source ->
            val file = session.getKtFileForPath(source.path)
                ?: return Problem(true, "Rename Kotlin Package Directory could not resolve ${source.nameExt}.")
            bag.add(refactoring, KotlinFindUsagesResultElement(OffsetRange(0, file.textLength), source))
        }
        bag.add(refactoring, KotlinRenameDirectoryApplyElement(selection, project, refactoring))
        return null
    }

    /** @return action-validated selection, or one resolved for compatibility callers. */
    private fun directorySelection(): KotlinMoveSourceSelection? = refactoring.selection
        ?.takeIf(KotlinMoveSourceSelection::isDirectory)
        ?: KotlinMoveSourceSelection.from(refactoring.directory)
            ?.takeIf(KotlinMoveSourceSelection::isDirectory)
}

/**
 * Renames a package directory physically and refreshes K2 after its path changes.
 *
 * @param selection recursive Kotlin descendants of the selected directory
 * @param project owning NetBeans project whose session must discover the new path
 * @param refactoring directory rename request
 */
class KotlinRenameDirectoryApplyElement(
    private val selection: KotlinMoveSourceSelection,
    private val project: org.netbeans.api.project.Project,
    private val refactoring: KotlinRenameDirectoryRefactoring,
) : SimpleRefactoringElementImplementation() {
    private var transaction: KotlinRefactoringTransaction? = null

    /** @return preview label. */
    override fun getText(): String = "Rename Kotlin package directory"

    /** @return preview label. */
    override fun getDisplayText(): String = text

    /** @return selected directory lookup. */
    override fun getLookup(): Lookup = Lookups.fixed(refactoring.directory)

    /** @return representative Kotlin file for preview placement. */
    override fun getParentFile(): FileObject = selection.representativeFile

    /** @return representative source bounds when editor support is available. */
    override fun getPosition(): PositionBounds? = positionFor(selection.representativeFile)

    /**
     * Renames the physical folder, recreates K2's session after the filesystem path changes, and
     * commits the retained transaction without changing Kotlin package directives or references.
     */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        try {
            val current = KotlinRefactoringTransaction()
            pending = current
            current.renameFolder(refactoring.directory, refactoring.newName)
            current.applyFolderRenamesNow()
            KotlinAnalysisAPISession.invalidate(project)
            val refreshedSession = KotlinAnalysisAPISession.getSession(project)
            selection.sourceFiles.forEach { source ->
                check(refreshedSession.getKtFileForPath(source.path) != null) {
                    "Rename Kotlin Package Directory could not resolve ${source.nameExt} after folder rename."
                }
            }
            current.commit()
            transaction = current
            pending = null
        } catch (error: Throwable) {
            KotlinLogger.INSTANCE.logException("KotlinRenameDirectoryApplyElement.performChange failed", error)
            rollbackAfterFailure(pending, error, "KotlinRenameDirectoryApplyElement rollback failed")
            throw error
        } finally {
            KotlinAnalysisAPISession.invalidate(project)
        }
    }

    /** Restores the original physical directory name and refreshes K2's filesystem view. */
    override fun undoChange() {
        runCatching { transaction?.undo(); transaction = null; KotlinAnalysisAPISession.invalidate(project) }
            .onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinRenameDirectoryApplyElement.undoChange failed", error) }
    }
}

/**
 * Rolls back a transaction after an apply failure without concealing the originating failure.
 *
 * @param transaction transaction that may already have changed documents or paths
 * @param originalError failure that NetBeans must receive
 * @param logMessage diagnostic context for an additional rollback failure
 */
private fun rollbackAfterFailure(
    transaction: KotlinRefactoringTransaction?,
    originalError: Throwable,
    logMessage: String,
) {
    try {
        transaction?.rollback()
    } catch (rollbackError: Throwable) {
        originalError.addSuppressed(rollbackError)
        KotlinLogger.INSTANCE.logException(logMessage, rollbackError)
    }
}

/** Validates a physical Kotlin source before creating its filesystem rename lifecycle. */
private fun validateKotlinFile(file: FileObject): Problem? = when {
    !file.isValid || file.isFolder -> Problem(true, "Rename Kotlin File requires a valid Kotlin source file.")
    file.ext != "kt" -> Problem(true, "Rename Kotlin File requires a .kt source file.")
    else -> null
}

/** Validates an explicit Kotlin file or package-directory basename. */
private fun validateIdentifier(name: String): Problem? = if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
    null
} else {
    Problem(true, "The new Kotlin file or package-directory name must be a Kotlin identifier.")
}

/** Creates position bounds for one preview source when a CloneableEditorSupport is available. */
private fun positionFor(file: FileObject): PositionBounds? = runCatching {
    val support = org.openide.loaders.DataObject.find(file).lookup.lookup(CloneableEditorSupport::class.java) ?: return null
    PositionBounds(support.createPositionRef(0, Bias.Forward), support.createPositionRef(0, Bias.Backward))
}.getOrNull()
