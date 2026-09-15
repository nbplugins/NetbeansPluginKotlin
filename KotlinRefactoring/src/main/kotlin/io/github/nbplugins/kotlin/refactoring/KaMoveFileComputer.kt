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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.MoveImport
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2MoveFilesHandler
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.findMoveFileUsages
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.prepareMovedFileForPackage
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Discovers whether a Kotlin file can participate in the Move File refactoring.
 *
 * The computer deliberately separates discovery from physical filesystem mutation: NetBeans owns
 * the latter through its refactoring transaction, while the later apply phase drives the ported K2
 * usage/conflict logic against writable session PSI.
 *
 * @param ktFile writable K2-session PSI for the file selected for movement
 */
class KaMoveFileComputer(
    private val ktFile: KtFile,
) {

    /** Represents the result of validating a selected Kotlin source file. */
    sealed class Outcome {
        /** The selected PSI has no physical path and cannot be moved by NetBeans. */
        data object NotApplicable : Outcome()

        /** Discovery failed unexpectedly. */
        data class Error(val error: Throwable) : Outcome()

        /** Discovery succeeded and [result] describes the movable source file. */
        data class Ready(val result: KaMoveFileResult) : Outcome()
    }

    /**
     * Validates the selected file and describes its package-update eligibility.
     *
     * @return [Outcome.Ready] for a physical Kotlin source file, [Outcome.NotApplicable] when its
     *         session PSI has no physical path, or [Outcome.Error] if discovery fails
     */
    fun compute(): Outcome = try {
        val path = ktFile.virtualFile?.path ?: return Outcome.NotApplicable
        Outcome.Ready(
            KaMoveFileResult(
                sourceFilePath = path,
                fileName = ktFile.name,
                packageName = ktFile.packageFqName.asString(),
                packageMayBeUpdated = packageMatchesDirectory(path, ktFile.packageFqName.asString()),
            )
        )
    } catch (error: Throwable) {
        Outcome.Error(error)
    }

    /**
     * Applies the ported IDEA K2 file-move semantics to [files] before NetBeans persists their
     * physical move.
     *
     * The caller supplies session-owned writable PSI for the files and a real disk-backed
     * [targetDirectory]. The adapter performs the same K2 sequence as IDEA's
     * `MoveFilesOrDirectoriesProcessor`: find Kotlin usages, collect Kotlin conflicts, rewrite
     * eligible package directives, create the old-to-new declaration map, and retarget Kotlin
     * references. It deliberately does not move VFS entries; NetBeans must perform that irreversible
     * operation through its transaction only after this method reports [ApplyOutcome.Success].
     *
     * @param files writable K2 PSI files selected for the move
     * @param targetDirectory real target directory used for conflict resolution
     * @param targetPackage package selected by NetBeans for the destination
     * @param updateReferences whether Kotlin external references should be retargeted
     * @return conflicts without mutation, a success containing every changed PSI file, or an error
     */
    fun apply(
        files: Collection<KtFile>,
        targetDirectory: PsiDirectory,
        targetPackage: FqName,
        updateReferences: Boolean,
    ): ApplyOutcome = apply(
        files.associateWith { MoveTarget(targetDirectory, targetPackage) },
        updateReferences,
    )

    /**
     * Applies K2 Move File semantics for sources whose final directories and packages differ.
     *
     * The directory Move command supplies one [MoveTarget] per source. K2 usage discovery occurs
     * before any source is mutated and retargeting receives one aggregate old-to-new map, which is
     * essential when two moved descendants refer to one another across relative directories.
     *
     * @param targets physical source PSI mapped to its final directory and declared package
     * @param updateReferences whether supported external Kotlin references should be retargeted
     * @return conflicts without mutation, changed texts with original snapshots, or an error
     */
    fun apply(targets: Map<KtFile, MoveTarget>, updateReferences: Boolean): ApplyOutcome {
        return try {
            require(targets.isNotEmpty()) { "Move File requires at least one Kotlin file." }
            val files = targets.keys.toList()
            val handler = K2MoveFilesHandler()
            // ProjectFileIndex and JavaDirectoryService cannot reconstruct the source-root-relative
            // directory of this session's LightVirtualFiles. Calling K2MoveFilesHandler.findUsages()
            // or needsUpdate() would therefore suppress valid work before its actual K2 semantic
            // branch. Use the same package/directory criterion against the real path, then retain
            // the upstream handler for package rewrite, conflict detection, declaration mapping,
            // and reference retargeting.
            val eligibleFiles = files.filter { file ->
                file.virtualFile?.path?.let { path -> packageMatchesDirectory(path, file.packageFqName.asString()) } == true
            }
            eligibleFiles.forEach(handler::markRequiresUpdate)
            val usages = if (updateReferences) {
                eligibleFiles.flatMap { file ->
                    file.findMoveFileUsages(
                        searchInCommentsAndStrings = false,
                        searchForText = false,
                        targetPackage = targets.getValue(file).targetPackage,
                    )
                }.distinct()
            } else {
                emptyList()
            }
            val conflicts = MultiMap<PsiElement, String>()
            files.groupBy { targets.getValue(it).targetDirectory }.forEach { (directory, groupedFiles) ->
                handler.detectConflicts(conflicts, groupedFiles.toTypedArray(), usages.toTypedArray(), directory)
            }
            if (!conflicts.isEmpty) {
                ApplyOutcome.Conflicts(conflicts.values().toList())
            } else {
                val movedImportTargets = files.flatMap { file ->
                    file.declarations.mapNotNull { declaration ->
                        (declaration as? KtNamedDeclaration)?.name?.let { name ->
                            MoveImport(file.packageFqName, name) to targets.getValue(file).targetPackage
                        }
                    }
                }.toMap()
                // K2 retargeting intentionally excludes import directives because mutating an import
                // path through bindToElement() destabilizes standalone PSI. Discover direct imports
                // separately, then rewrite their full directives below with KtPsiFactory.
                val importOnlyUsageFiles = KotlinMoveUsageSearchService.getInstance()
                    ?.findImportingFiles(files.first().project, movedImportTargets)
                    .orEmpty()
                val usageFiles = (filesWithUsages(usages) + importOnlyUsageFiles).distinct()
                val originalTexts = (files + usageFiles).distinct().mapNotNull { file ->
                    file.virtualFile?.path?.let { path -> path to file.text }
                }.toMap()
                val oldToNew = linkedMapOf<PsiElement, PsiElement>()
                val changedFiles = linkedMapOf<String, String>()
                files.forEach { file ->
                    val target = targets.getValue(file)
                    handler.prepareMovedFileForPackage(
                        file = file,
                        targetPackage = target.targetPackage,
                        rewritePackage = file in eligibleFiles,
                        oldToNewMap = oldToNew,
                    )
                    file.virtualFile?.path?.let { path -> changedFiles[path] = file.text }
                }
                if (updateReferences) handler.retargetUsages(usages, oldToNew)
                usageFiles.filterNot { it in files }.forEach { file ->
                    rewriteMovedImports(file, movedImportTargets)
                    file.virtualFile?.path?.let { path -> changedFiles[path] = file.text }
                }
                ApplyOutcome.Success(changedFiles, originalTexts)
            }
        } catch (error: Throwable) {
            ApplyOutcome.Error(error)
        }
    }

    /** A source's physical K2 target directory and final declared package. */
    data class MoveTarget(val targetDirectory: PsiDirectory, val targetPackage: FqName)

    /** Represents the result of applying K2-only Move File semantics. */
    sealed class ApplyOutcome {
        /** K2 found blocking semantic conflicts and left PSI unchanged. */
        data class Conflicts(val messages: List<String>) : ApplyOutcome()

        /**
         * K2 changed package directives and/or Kotlin references.
         *
         * @param changedFiles final texts NetBeans must persist
         * @param originalTexts pre-K2 snapshots for the same participants, used by transaction Undo
         */
        data class Success(
            val changedFiles: Map<String, String>,
            val originalTexts: Map<String, String>,
        ) : ApplyOutcome()

        /** The K2 semantic operation failed. */
        data class Error(val error: Throwable) : ApplyOutcome()
    }

    /**
     * Retains Kotlin files whose text may change during external-usage retargeting.
     *
     * K2 replaces the usage PSI while rebinding it, so [UsageInfo.element] can be invalid or absent
     * after `retargetUsages`. Capture containing files before that mutation so NetBeans can persist
     * updated imports and qualified references afterward.
     */
    private fun filesWithUsages(usages: Collection<UsageInfo>): List<KtFile> =
        usages.mapNotNull { usage -> usage.element?.containingFile as? KtFile }.distinct()

    /**
     * Rewrites imports of top-level declarations moved from their former package to [targetPackage].
     *
     * Standalone K2's reference-rebinding service intentionally skips imports because replacing an
     * import-path segment invalidates the whole import list without an IDEA document synchronizer.
     * The old-to-new map for a File Move preserves declaration identity, so update the direct import
     * path explicitly after K2 retargeting; code usages remain handled by the upstream K2 engine.
     */
    private fun rewriteMovedImports(usageFile: KtFile, movedImports: Map<MoveImport, FqName>) {
        if (movedImports.isEmpty()) return
        val factory = KtPsiFactory(usageFile.project)
        usageFile.importDirectives.forEach { directive ->
            if (directive.aliasName != null || directive.isAllUnder) return@forEach
            val importedFqName = directive.importedFqName ?: return@forEach
            val importedName = directive.importedName ?: return@forEach
            val targetPackage = movedImports[MoveImport(importedFqName.parent(), importedName.asString())]
                ?: return@forEach
            directive.replace(factory.createImportDirective(ImportPath(targetPackage.child(importedName), false)))
        }
    }


    /**
     * Determines whether [packageName]'s segments are the terminal directory segments of [path].
     *
     * A standalone K2 session does not expose IDEA's source-root model to a `LightVirtualFile`.
     * Comparing only the terminal package suffix preserves the relevant K2MoveFilesHandler rule:
     * package rewriting is safe only when the source-root-relative directory agrees with the
     * declared package. The root package is safe because every directory layout represents it.
     */
    private fun packageMatchesDirectory(path: String, packageName: String): Boolean {
        if (packageName.isEmpty()) return true
        val parentSegments = path.substringBeforeLast('/', missingDelimiterValue = "")
            .split('/')
            .filter(String::isNotEmpty)
        val packageSegments = packageName.split('.')
        return parentSegments.takeLast(packageSegments.size) == packageSegments
    }
}

/**
 * Immutable data required by the NetBeans Move File UI and apply phase.
 *
 * @param sourceFilePath absolute physical path of the selected Kotlin file
 * @param fileName source file name, including extension
 * @param packageName declared Kotlin package, or an empty string for the root package
 * @param packageMayBeUpdated whether the declared package agrees with the file's directory layout
 */
data class KaMoveFileResult(
    val sourceFilePath: String,
    val fileName: String,
    val packageName: String,
    val packageMayBeUpdated: Boolean,
)
