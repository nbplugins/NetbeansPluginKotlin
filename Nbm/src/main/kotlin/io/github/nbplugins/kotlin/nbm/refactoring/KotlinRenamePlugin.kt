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
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectSourceGroups
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaChangePackageComputer
import io.github.nbplugins.kotlin.refactoring.KaRenameComputer
import io.github.nbplugins.kotlin.refactoring.KotlinImportBindingCheck
import io.github.nbplugins.kotlin.refactoring.KotlinSourceNames
import io.github.nbplugins.kotlin.refactoring.TextChange
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.project.Project
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
import javax.swing.text.Position
import javax.swing.text.Position.Bias
import java.io.File
import java.util.LinkedHashMap

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
    override fun fastCheckParameters(): Problem? =
        validateIdentifier(refactoring.newName)
            ?: validateClassName(refactoring.newName, refactoring.updatePrimaryClass)

    /** @return the same validation used before UI confirmation. */
    override fun checkParameters(): Problem? = fastCheckParameters()

    /** Cancels no external work because the operation runs within the lifecycle. */
    override fun cancelRequest() = Unit

    /** Adds one preview and atomic transaction apply element. */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        preCheck()?.let { return it }
        fastCheckParameters()?.let { return it }
        classConflictProblem()?.let { return it }
        bag.add(refactoring, KotlinFindUsagesResultElement(OffsetRange.NONE, refactoring.file))
        bag.add(refactoring, KotlinRenameFileApplyElement(refactoring))
        return null
    }

    /**
     * Reports a redeclaration conflict before the semantic rename is staged.
     *
     * The rename writes the new class name verbatim and does not run Kotlin's redeclaration checks,
     * so an existing class, object, or type alias with the same name in the same package would
     * become a second declaration of the same FQN.
     *
     * @return a blocking problem when the target class name is already declared, otherwise `null`
     */
    private fun classConflictProblem(): Problem? {
        if (!refactoring.updatePrimaryClass) return null
        val project = ProjectUtils.getKotlinProjectForFileObject(refactoring.file) ?: return null
        KotlinAnalysisAPISession.invalidate(project)
        val session = KotlinAnalysisAPISession.getSession(project)
        session.synchronizeOpenDocuments()
        val source = session.getKtFileForPath(refactoring.file.path) ?: return null
        val primaryClass = source.declarations.filterIsInstance<KtClass>()
            .firstOrNull { declaration -> declaration.name == refactoring.file.name }
            ?: return null
        val files = session.fileMap.values.mapNotNull { lightFile -> session.getKtFileForPath(lightFile.path) }
        val conflict = findRenameConflict(source, primaryClass, refactoring.newName, files)
        if (conflict != null) {
            val packageName = source.packageFqName
            val location = if (packageName.isRoot) "the root package" else "package ${packageName.asString()}"
            return Problem(
                true,
                "The Kotlin name ${refactoring.newName} is already declared in $location (${conflict.containingKtFile.name}).",
            )
        }
        val caller = findCallerScopeConflict(source, primaryClass, refactoring.newName, files)
            ?: return captureConflictProblem(source, primaryClass, files)
        return Problem(
            true,
            "Renaming the Kotlin class ${primaryClass.name} to ${refactoring.newName} would change " +
                "which ${refactoring.newName} the references in ${caller.name} use.",
        )
    }

    /**
     * Reports a renamed reference whose new spelling would bind to another declaration.
     *
     * The rename rewrites unqualified references verbatim, so a file that already binds the new
     * name lexically — a member function, property, parameter, or local — would make the rewritten
     * reference resolve to that binding. A constructor call then becomes a call of the member
     * function and the code still compiles with a different meaning.
     *
     * @param source KtFile declaring the renamed class
     * @param primaryClass class being renamed
     * @param files project files searched for rewritten references
     * @return a blocking problem when a rewritten reference would capture, otherwise `null`
     */
    private fun captureConflictProblem(
        source: KtFile,
        primaryClass: KtClass,
        files: List<KtFile>,
    ): Problem? {
        val offset = primaryClass.nameIdentifier?.textRange?.startOffset ?: return null
        val capture = KaRenameComputer(
            cursorKtFile = source,
            offset = offset,
            projectKtFiles = files.toSet(),
            newName = refactoring.newName,
        ).findCaptureConflicts() ?: return null
        return Problem(
            true,
            "Renaming the Kotlin class ${primaryClass.name} to ${refactoring.newName} would make " +
                "the rewritten references in ${capture.name} resolve to a different declaration.",
        )
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
            if (refactoring.file.name == refactoring.newName) return
            val current = KotlinRefactoringTransaction()
            pending = current
            if (refactoring.updatePrimaryClass) {
                stagePrimaryClassRename(current)
            }
            current.renameFile(refactoring.file, refactoring.newName, refactoring.file.ext)
            current.commit()
            transaction = current
            pending = null
        } catch (error: Throwable) {
            KotlinLogger.INSTANCE.logException("KotlinRenameFileApplyElement.performChange failed", error)
            rollbackAfterFailure(pending, error, "KotlinRenameFileApplyElement rollback failed")
            throw error
        } finally {
            if (refactoring.updatePrimaryClass) {
                ProjectUtils.getKotlinProjectForFileObject(refactoring.file)?.let(KotlinAnalysisAPISession::invalidate)
            }
        }
    }

    /**
     * Stages the semantic rename of the single top-level class matching the old file basename.
     *
     * @param current transaction that writes the K2-generated texts before renaming the file
     */
    private fun stagePrimaryClassRename(current: KotlinRefactoringTransaction) {
        val project = ProjectUtils.getKotlinProjectForFileObject(refactoring.file) ?: return
        KotlinAnalysisAPISession.invalidate(project)
        val session = KotlinAnalysisAPISession.getSession(project)
        session.synchronizeOpenDocuments()
        val source = session.getKtFileForPath(refactoring.file.path) ?: return
        val primaryClass = source.declarations.filterIsInstance<KtClass>()
            .firstOrNull { it.name == refactoring.file.name }
            ?: return
        // The session knows every registered source, including custom Gradle source roots outside
        // `src`, which `KotlinPsiManager.getFilesByProject` does not walk. Search all of them so
        // the file's own constructor calls are retargeted as well.
        val allKtFiles = session.fileMap.values
            .mapNotNull { lightFile -> session.getKtFileForPath(lightFile.path) }
            .toSet() + source
        val allFiles = KotlinPsiManager.getFilesByProject(project)
        val changes = KaRenameComputer(
            source,
            primaryClass.nameIdentifier?.textRange?.startOffset ?: return,
            allKtFiles,
            refactoring.newName,
        ).compute() ?: return
        val sourceByPath = allFiles.associateBy(FileObject::getPath)
        changes.forEach { (path, textChanges) ->
            val file = sourceByPath[path] ?: FileUtil.toFileObject(FileUtil.normalizeFile(File(path)))
                ?: error("Rename Kotlin File could not resolve changed file $path.")
            val document = current.captureExisting(file)
            val originalText = document.getText(0, document.length)
            val finalText = applyTextChanges(originalText, textChanges)
            current.stageHunkText(file, finalText, project, openCaretOffset(file))
        }
    }

    /** Restores the original basename and any matching primary-class rename. */
    override fun undoChange() {
        runCatching { transaction?.undo(); transaction = null }
            .onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinRenameFileApplyElement.undoChange failed", error) }
        if (refactoring.updatePrimaryClass) {
            ProjectUtils.getKotlinProjectForFileObject(refactoring.file)?.let(KotlinAnalysisAPISession::invalidate)
        }
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

    /** @return operation name that matches whether package directives also change. */
    private val operationName: String
        get() = if (refactoring.updatePackage) "Rename Kotlin Package" else "Rename Kotlin Package Directory"

    /** @return a fatal problem when the selected folder has no Kotlin sources. */
    override fun preCheck(): Problem? = if (directorySelection() != null) {
        null
    } else {
        Problem(true, "$operationName requires a folder containing Kotlin files.")
    }

    /** @return a fatal problem when the target name is not a Kotlin identifier. */
    override fun fastCheckParameters(): Problem? = validateIdentifier(refactoring.newName)

    /** @return the same validation used before UI confirmation. */
    override fun checkParameters(): Problem? = fastCheckParameters()

    /** Cancels no external work because the operation runs within the lifecycle. */
    override fun cancelRequest() = Unit

    /**
     * Validates the derived packages and adds source previews plus one atomic apply element.
     *
     * An invalid path-derived package is reported here, before any refactoring work starts.
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val selection = directorySelection()
            ?: return Problem(true, "$operationName requires a folder containing Kotlin files.")
        fastCheckParameters()?.let { return it }
        val project = ProjectUtils.getKotlinProjectForFileObject(selection.representativeFile)
            ?: ProjectUtils.getValidProject()
            ?: return Problem(true, "$operationName could not find the source project.")
        if (refactoring.updatePackage && refactoring.directory.name != refactoring.newName) {
            val root = kotlinSourceRootFor(project, refactoring.directory)
                ?: return Problem(true, "$operationName could not find a Kotlin source root for ${refactoring.directory.path}.")
            try {
                selection.sourceFiles.forEach { source ->
                    KotlinPackageRenameMapping.renamedPackage(root, refactoring.directory, refactoring.newName, source)
                }
            } catch (error: IllegalArgumentException) {
                return Problem(true, error.message ?: "$operationName could not derive the new package names.")
            }
        }
        val session = KotlinAnalysisAPISession.getSession(project)
        selection.sourceFiles.forEach { source ->
            val file = session.getKtFileForPath(source.path)
                ?: return Problem(true, "$operationName could not resolve ${source.nameExt}.")
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
 * Renames a package directory physically and, when requested, its Kotlin packages and references.
 *
 * The semantic mode runs K2 on the original paths, stages every returned text change, and lets one
 * transaction write those documents before it renames the folder. This mirrors Move Kotlin File:
 * conflicts are reported before any physical change, and undo restores paths and texts together.
 *
 * @param selection recursive Kotlin descendants of the selected directory
 * @param project owning NetBeans project whose session must discover the new path
 * @param refactoring directory rename request
 */
class KotlinRenameDirectoryApplyElement(
    private val selection: KotlinMoveSourceSelection,
    private val project: Project,
    private val refactoring: KotlinRenameDirectoryRefactoring,
) : SimpleRefactoringElementImplementation() {
    private var transaction: KotlinRefactoringTransaction? = null
    private var undoCaretOffsets: Map<FileObject, Int> = emptyMap()

    /** @return preview label that reflects whether package directives also change. */
    override fun getText(): String =
        if (refactoring.updatePackage) "Rename Kotlin package" else "Rename Kotlin package directory"

    /** @return preview label. */
    override fun getDisplayText(): String = text

    /** @return selected directory lookup. */
    override fun getLookup(): Lookup = Lookups.fixed(refactoring.directory)

    /** @return representative Kotlin file for preview placement. */
    override fun getParentFile(): FileObject = selection.representativeFile

    /** @return representative source bounds when editor support is available. */
    override fun getPosition(): PositionBounds? = positionFor(selection.representativeFile)

    /**
     * Renames the selected folder and commits the retained transaction.
     *
     * Generic Rename also derives every descendant's new package from its post-rename path, updates
     * supported Kotlin references, and preserves open-editor carets. The explicit package-directory
     * action keeps the previous physical-only behavior and refreshes K2 after the path change.
     */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        try {
            if (refactoring.updatePackage && refactoring.directory.name == refactoring.newName) {
                return
            }
            val current = KotlinRefactoringTransaction()
            pending = current
            if (refactoring.updatePackage) {
                val carets = stageSemanticPackageRename(current)
                current.commit()
                carets.livePositions.forEach { (file, position) ->
                    restoreAfterEditorUpdate { restoreCaret(file, position.offset) }
                }
                undoCaretOffsets = carets.undoOffsets
            } else {
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
            }
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

    /**
     * Runs K2 Change Package semantics for the post-rename layout and stages every changed document.
     *
     * @param current transaction that will write the staged texts and then rename the folder
     * @return caret snapshots captured before staging, used after commit and on undo
     */
    private fun stageSemanticPackageRename(current: KotlinRefactoringTransaction): SemanticRenameCarets {
        val directory = refactoring.directory
        val root = kotlinSourceRootFor(project, directory)
            ?: error("Rename Kotlin Package could not find a Kotlin source root for ${directory.path}.")
        val packages = selection.sourceFiles.associateWith { source ->
            KotlinPackageRenameMapping.renamedPackage(root, directory, refactoring.newName, source)
        }
        // Register the physical rename first so an occupied target is rejected before K2 analysis.
        current.renameFolder(directory, refactoring.newName)
        KotlinAnalysisAPISession.invalidate(project)
        val session = KotlinAnalysisAPISession.getSession(project)
        session.synchronizeOpenDocuments()
        val sourceByPath = selection.sourceFiles.associateBy(FileObject::getPath)
        val targets = selection.sourceFiles.associate { source ->
            val file = session.getKtFileForPath(source.path)
                ?: error("Rename Kotlin Package could not resolve writable source PSI: ${source.nameExt}.")
            file to FqName(packages.getValue(source))
        }
        return when (val outcome = KaChangePackageComputer(targets.keys.first()).apply(targets, updateReferences = true)) {
            is KaChangePackageComputer.ApplyOutcome.Conflicts ->
                throw IllegalStateException(outcome.messages.joinToString("\n"))
            is KaChangePackageComputer.ApplyOutcome.Error -> throw outcome.error
            is KaChangePackageComputer.ApplyOutcome.Success -> {
                val undoOffsets = LinkedHashMap<FileObject, Int>()
                val livePositions = LinkedHashMap<FileObject, Position>()
                outcome.changedFiles.forEach { (path, text) ->
                    val file = sourceByPath[path] ?: FileUtil.toFileObject(FileUtil.normalizeFile(File(path)))
                        ?: error("Rename Kotlin Package could not resolve changed file $path.")
                    val document = current.captureExisting(file)
                    val originalOffset = openCaretOffset(file)?.coerceIn(0, document.length)
                    originalOffset?.let {
                        undoOffsets[file] = it
                        livePositions[file] = document.createPosition(it)
                    }
                    current.stageHunkText(file, text, project, originalOffset)
                }
                SemanticRenameCarets(undoOffsets, livePositions)
            }
        }
    }

    /** Restores the original physical directory name, document texts, and open-editor carets. */
    override fun undoChange() {
        runCatching {
            val current = transaction ?: return@runCatching
            val caretOffsets = undoCaretOffsets
            current.undo()
            transaction = null
            undoCaretOffsets = emptyMap()
            caretOffsets.forEach { (file, offset) -> restoreAfterEditorUpdate { restoreCaret(file, offset) } }
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinRenameDirectoryApplyElement.undoChange failed", error)
        }
        KotlinAnalysisAPISession.invalidate(project)
    }
}

/**
 * Caret snapshots for one semantic directory rename.
 *
 * @param undoOffsets pre-refactoring offsets restored by Undo Last Refactoring after texts are back
 * @param livePositions document-tracked positions used right after commit, once hunks have shifted
 */
private class SemanticRenameCarets(
    val undoOffsets: Map<FileObject, Int>,
    val livePositions: Map<FileObject, Position>,
)

/**
 * Produces the final source text after applying non-overlapping [changes] in descending order.
 *
 * @param originalText source text that produced the K2 offsets
 * @param changes K2 rename replacements for the source
 * @return source text with every replacement applied
 */
internal fun applyTextChanges(originalText: String, changes: List<TextChange>): String =
    StringBuilder(originalText).also { text ->
        changes.sortedByDescending(TextChange::start).forEach { change ->
            require(change.start in 0..text.length && change.end in change.start..text.length) {
                "Invalid rename range [${change.start}, ${change.end}) for ${text.length}-character source."
            }
            text.replace(change.start, change.end, change.newText)
        }
    }.toString()

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

/**
 * Finds the conventional Kotlin source root of [project] that contains [directory].
 *
 * The most specific (deepest) root wins, which keeps the derived package independent of any future
 * nested conventional root.
 */
private fun kotlinSourceRootFor(project: Project, directory: FileObject): FileObject? =
    KotlinProjectSourceGroups.findRoots(project.projectDirectory)
        .map { it.folder }
        .filter { FileUtil.isParentOf(it, directory) }
        .maxByOrNull { it.path.length }

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

/**
 * Rejects a basename that would become an invalid Kotlin class name in semantic mode.
 *
 * A physical file may be named after a hard keyword, but the matching top-level class is written
 * verbatim into source text, where a bare keyword is not a valid identifier.
 *
 * @param name requested basename, already accepted as a plain identifier
 * @param updatePrimaryClass whether generic Rename also renames the matching class
 * @return a fatal problem when the basename cannot name a Kotlin class
 */
private fun validateClassName(name: String, updatePrimaryClass: Boolean): Problem? =
    if (updatePrimaryClass && !KotlinSourceNames.isPlainIdentifier(name)) {
        Problem(true, "The new Kotlin class name must not be a Kotlin keyword: $name")
    } else {
        null
    }

/**
 * Finds a top-level declaration that already owns [newName] in the package of [source].
 *
 * Renaming a file together with its primary class writes the new name verbatim, so a class,
 * object, or type alias that already has that name in the same package would be redeclared. A
 * top-level function with the same call signature as the renamed primary constructor conflicts
 * with it as well: `class Before` plus `fun After()` in one package cannot both become `After`.
 *
 * @param source file whose matching class is renamed
 * @param primaryClass top-level class renamed together with the file
 * @param newName requested class name
 * @param files Kotlin sources of the project
 * @return the first conflicting declaration, or `null` when the name is free
 */
internal fun findRenameConflict(
    source: KtFile,
    primaryClass: KtClass,
    newName: String,
    files: Collection<KtFile>,
): KtNamedDeclaration? {
    val packageName = source.packageFqName
    val constructorParameterTypes = primaryClass.primaryConstructorParameters
        .map { parameter -> parameter.typeReference?.text }
    return files.asSequence()
        .filter { file -> file.packageFqName == packageName }
        .flatMap { file -> file.declarations.asSequence() }
        .filter { declaration ->
            declaration is KtClassOrObject || declaration is KtTypeAlias ||
                (declaration is KtNamedFunction &&
                    declaration.conflictsWithConstructor(constructorParameterTypes))
        }
        .map { declaration -> declaration as KtNamedDeclaration }
        .filter { declaration -> !declaration.isSameDeclaration(primaryClass) }
        .firstOrNull { declaration -> declaration.name == newName }
}

/**
 * Finds a caller whose existing references would resolve differently after the import rewrite.
 *
 * Renaming the primary class turns `import p.Before` into `import p.After`. A caller that already
 * refers to a different top-level `After` — from its own package or another import — keeps those
 * references untouched during discovery, because they do not resolve to the renamed class. The
 * rewritten import takes precedence over them afterwards, so they silently start using the
 * renamed class. The rename is rejected instead of changing unrelated call sites.
 *
 * @param source file whose primary class is renamed
 * @param primaryClass class renamed together with the file
 * @param newName requested class name
 * @param files Kotlin sources of the project
 * @return the first caller whose references would change their target, or `null` when none would
 */
internal fun findCallerScopeConflict(
    source: KtFile,
    primaryClass: KtClass,
    newName: String,
    files: Collection<KtFile>,
): KtFile? {
    val className = primaryClass.name ?: return null
    val oldFqName = source.packageFqName.child(Name.identifier(className))
    return files.firstOrNull { file ->
        file.virtualFile?.path != source.virtualFile?.path &&
            file.importDirectives.any { directive ->
                !directive.isAllUnder && directive.aliasName == null &&
                    directive.importedFqName == oldFqName
            } &&
            KotlinImportBindingCheck.hasConflictingReference(file, newName, setOf(primaryClass))
    }
}

/**
 * Tells whether this top-level function has the same call signature as a primary constructor.
 *
 * Matching parameter count and type spellings is what makes Kotlin report conflicting overloads.
 * An extension function has an implicit receiver and therefore cannot conflict with a constructor.
 *
 * @param constructorParameterTypes type spellings of the renamed primary constructor parameters
 * @return `true` when a call to either declaration could not be told apart
 */
private fun KtNamedFunction.conflictsWithConstructor(constructorParameterTypes: List<String?>): Boolean {
    if (receiverTypeReference != null) return false
    if (valueParameters.size != constructorParameterTypes.size) return false
    return valueParameters.indices.all { index ->
        val functionType = valueParameters[index].typeReference?.text
        val constructorType = constructorParameterTypes[index]
        functionType == null || constructorType == null || functionType == constructorType
    }
}

/** Tells whether this declaration is [primaryClass], tolerating distinct PSI instances of it. */
private fun KtNamedDeclaration.isSameDeclaration(primaryClass: KtClass): Boolean =
    this === primaryClass ||
        (this is KtClass &&
            name == primaryClass.name &&
            containingKtFile.virtualFile?.path == primaryClass.containingKtFile.virtualFile?.path &&
            textRange.startOffset == primaryClass.textRange.startOffset)

/** Creates position bounds for one preview source when a CloneableEditorSupport is available. */
private fun positionFor(file: FileObject): PositionBounds? = runCatching {
    val support = org.openide.loaders.DataObject.find(file).lookup.lookup(CloneableEditorSupport::class.java) ?: return null
    PositionBounds(support.createPositionRef(0, Bias.Forward), support.createPositionRef(0, Bias.Backward))
}.getOrNull()
