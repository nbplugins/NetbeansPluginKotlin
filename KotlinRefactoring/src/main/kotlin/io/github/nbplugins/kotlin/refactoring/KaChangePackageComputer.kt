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

import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.MoveImport
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2ChangePackageDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2ChangePackageRefactoringProcessor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Applies IDEA K2 Change Package semantics without its IDE-specific processor lifecycle.
 *
 * NetBeans supplies writable session PSI and persists the returned text atomically. The computer
 * retains the upstream algorithm's usage discovery, conflict pass, package rewrite, declaration
 * identity map, and reference retargeting while deliberately leaving filesystem mutations to no one:
 * changing a package never changes a physical path.
 *
 * @param ktFile representative writable K2-session source file
 */
class KaChangePackageComputer(
    private val ktFile: KtFile,
) {

    /** Describes the result of applying package changes to one or more Kotlin source files. */
    sealed class ApplyOutcome {
        /** K2 found blocking semantic conflicts and did not mutate any PSI. */
        data class Conflicts(val messages: List<String>) : ApplyOutcome()

        /** K2 produced final texts and pre-mutation snapshots for every changed participant. */
        data class Success(
            val changedFiles: Map<String, String>,
            val originalTexts: Map<String, String>,
        ) : ApplyOutcome()

        /** An unexpected K2 or PSI failure prevented the operation. */
        data class Error(val error: Throwable) : ApplyOutcome()
    }

    /**
     * Changes every source in [targets] to its requested package and retargets supported usages.
     *
     * @param targets writable source PSI mapped to their final declared packages
     * @param updateReferences whether supported external Kotlin references and direct imports update
     * @return conflicts without mutation, changed texts with original snapshots, or an error
     */
    fun apply(targets: Map<KtFile, FqName>, updateReferences: Boolean): ApplyOutcome {
        return try {
            require(targets.isNotEmpty()) { "Change Package requires at least one Kotlin file." }
            val files = targets.keys.toList()
            require(files.all { it.virtualFile?.path != null }) { "Change Package requires physical Kotlin files." }

            val processors = targets.entries.groupBy({ it.value }, { it.key }).map { (targetPackage, groupedFiles) ->
                K2ChangePackageRefactoringProcessor(
                    K2ChangePackageDescriptor(
                        project = ktFile.project,
                        files = groupedFiles.toSet(),
                        target = targetPackage,
                        searchForText = false,
                        searchInComments = false,
                    ),
                )
            }
            // Use the copied upstream Change Package processor rather than the F4.1 Move File helper:
            // its findUsages() marks whole-file internal references. That is what forces a same-package,
            // no-import usage in another source file to gain an import after its declaration changes package.
            val usages = if (updateReferences) processors.flatMap { it.findUsages().asIterable() }.distinct() else emptyList()
            val conflicts = processors.flatMap { processor ->
                processor.findConflicts(usages.filterIsInstance<MoveRenameUsageInfo>()).values()
            }
            if (conflicts.isNotEmpty()) return ApplyOutcome.Conflicts(conflicts.distinct())

            val movedImports = files.flatMap { file ->
                file.declarations.mapNotNull { declaration ->
                    (declaration as? KtNamedDeclaration)?.name?.let { name ->
                        MoveImport(file.packageFqName, name) to targets.getValue(file)
                    }
                }
            }.toMap()
            val samePackageImportUpdates = if (updateReferences) {
                findSamePackageImportUpdates(files, targets)
            } else {
                emptyMap()
            }
            val importOnlyUsageFiles = if (updateReferences) {
                KotlinMoveUsageSearchService.getInstance()
                    ?.findImportingFiles(ktFile.project, movedImports)
                    .orEmpty()
            } else {
                emptyList()
            }
            val usageFiles = (filesWithUsages(usages) + importOnlyUsageFiles + samePackageImportUpdates.keys).distinct()
            val originalTexts = (files + usageFiles).distinct().mapNotNull { file ->
                file.virtualFile?.path?.let { path -> path to file.text }
            }.toMap()
            val oldToNew = linkedMapOf<com.intellij.psi.PsiElement, com.intellij.psi.PsiElement>()
            processors.forEach { processor -> processor.prepareRefactoring(oldToNew) }
            if (updateReferences) processors.first().retargetUsages(usages, oldToNew)

            val changedFiles = linkedMapOf<String, String>()
            files.forEach { file -> file.virtualFile?.path?.let { changedFiles[it] = file.text } }
            usageFiles.filterNot { it in files }.forEach { file ->
                rewriteMovedImports(file, movedImports)
                val changedText = addSamePackageImports(file, samePackageImportUpdates[file].orEmpty())
                file.virtualFile?.path?.let { changedFiles[it] = changedText }
            }
            ApplyOutcome.Success(changedFiles, originalTexts)
        } catch (error: Throwable) {
            ApplyOutcome.Error(error)
        }
    }

    /**
     * Finds old-package simple references that will lose their implicit package visibility.
     *
     * The standalone K2 retargeter excludes these whole-file internal usages, so identify them
     * before changing directives and later add only the imports required to preserve binding.
     */
    private fun findSamePackageImportUpdates(
        movedFiles: Collection<KtFile>,
        targets: Map<KtFile, FqName>,
    ): Map<KtFile, Set<FqName>> {
        val movedDeclarations = movedFiles.flatMap { file ->
            file.declarations.filterIsInstance<KtNamedDeclaration>().mapNotNull { declaration ->
                declaration.name?.let { name -> declaration to targets.getValue(file).child(org.jetbrains.kotlin.name.Name.identifier(name)) }
            }
        }.toMap()
        if (movedDeclarations.isEmpty()) return emptyMap()
        val session = KotlinMoveUsageSearchService.getInstance() ?: return emptyMap()
        val updates = linkedMapOf<KtFile, MutableSet<FqName>>()
        movedDeclarations.forEach { (declaration, targetFqName) ->
            session.findUsages(declaration).forEach { reference ->
                val file = reference.element?.containingFile as? KtFile ?: return@forEach
                if (file in movedFiles || file.packageFqName != declaration.containingKtFile.packageFqName) return@forEach
                updates.getOrPut(file, ::linkedSetOf) += targetFqName
            }
        }
        return updates
    }

    /**
     * Adds imports for former same-package declarations that no longer resolve implicitly.
     *
     * The standalone K2 retargeter creates the first import list without its package separator.
     * Rebuild this narrow header text rather than inserting PSI whitespace, which the standalone
     * parser drops while adding the import-list node.
     *
     * @return final source text with a canonical package/import boundary
     */
    private fun addSamePackageImports(usageFile: KtFile, imports: Set<FqName>): String {
        if (imports.isEmpty()) return usageFile.text
        val packageDirective = usageFile.packageDirective ?: return usageFile.text
        val importList = usageFile.importList
        val existingImports = importList?.imports.orEmpty().map { it.text }
        val existingFqNames = usageFile.importDirectives.mapNotNull { it.importedFqName }.toSet()
        val missingImports = imports.filterNot { it in existingFqNames }.map { imported ->
            "import ${imported.asString()}"
        }
        val directives = existingImports + missingImports
        if (directives.isEmpty()) return usageFile.text
        val headerEnd = importList?.textRange?.endOffset ?: packageDirective.textRange.endOffset
        return usageFile.text.substring(0, packageDirective.textRange.endOffset) +
            "\n\n${directives.joinToString(separator = "\n")}" +
            usageFile.text.substring(headerEnd)
    }

    /** Captures usage files before K2 reference rebinding can invalidate individual usage elements. */
    private fun filesWithUsages(usages: Collection<UsageInfo>): List<KtFile> =
        usages.mapNotNull { usage -> usage.element?.containingFile as? KtFile }.distinct()

    /**
     * Replaces direct imports after K2 code retargeting.
     *
     * Standalone K2 cannot safely rebind a segment within an import directive because that can
     * invalidate the complete import list before an IDEA document synchronizer updates it.
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
}
