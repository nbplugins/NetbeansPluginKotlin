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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveOperationDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2MoveDeclarationsRefactoringProcessor
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf

/**
 * Headless analysis + execution engine for the **Move Declaration** refactoring (E9.7).
 *
 * Drives IDEA's real K2 move engine directly:
 * [K2MoveOperationDescriptor.Declarations][org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveOperationDescriptor.Companion.Declarations]
 * builds the descriptor, [K2MoveDeclarationsRefactoringProcessor] performs usage search, conflict
 * detection, and the move+retarget itself. Following the same convention as every other E9.x
 * refactoring, `BaseRefactoringProcessor.run()` (IDEA's modal pipeline) is never invoked — the
 * three steps ([findUsages], [preprocessUsages], [performRefactoring]) are called directly, and
 * conflicts are surfaced to the caller (the NetBeans `RefactoringPlugin`/`RefactoringUI`) instead
 * of showing IDEA's `ConflictsDialog`.
 *
 * @param ktFile      the source file
 * @param caretOffset caret position within the file
 */
class KaMoveDeclarationComputer(
    private val ktFile: KtFile,
    private val caretOffset: Int,
) {
    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret is not inside a top-level named declaration. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the caller needs to prompt for a target. */
        data class Ready(val result: KaMoveDeclarationResult) : Outcome()
    }

    /** Outcome of actually performing the move. */
    sealed class MoveOutcome {
        /** Conflicts were found; [messages] are human-readable descriptions. Move was NOT performed. */
        data class Conflicts(val messages: List<String>) : MoveOutcome()

        /**
         * The move completed successfully (in-memory PSI only — the underlying VFS in this
         * standalone environment is read-only, see [resolveDirectory]'s doc comment). [sourceText]
         * and [targetText] are the resulting file contents; the caller must persist them itself
         * (e.g. via a live NetBeans `Document` for the source, and `FileObject.getOutputStream()`
         * for the target — mirroring how Copy Declaration writes its result).
         */
        data class Success(val sourceText: String, val targetText: String) : MoveOutcome()

        /** The move failed with [error]. */
        data class Error(val error: Throwable) : MoveOutcome()
    }

    fun compute(): Outcome = try {
        computeInternal()
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    /**
     * Finds the top-level [KtNamedDeclaration] the caret refers to — either directly (caret
     * inside the declaration itself) or via a reference (caret on a call site / any other usage
     * of the declaration, anywhere in the project, per this project's "cursor-on-declaration-or-
     * usage" convention — see `docs/development-plan.md`'s E9 strategy section).
     */
    private fun findDeclaration(): KtNamedDeclaration? {
        val leaf = ktFile.findElementAt(caretOffset) ?: return null

        // Caret on a usage: resolve via K2 to the declaring element, in any file.
        val ref = PsiTreeUtil.getParentOfType(leaf, KtNameReferenceExpression::class.java, false)
        if (ref != null) {
            val resolved = runCatching {
                analyze(ref) { ref.mainReference?.resolveToSymbol()?.psi }
            }.getOrNull()
            val topLevel = (resolved as? KtNamedDeclaration)?.takeIf { it.parent is KtFile }
            if (topLevel != null) return topLevel
        }

        // Caret directly on/inside the declaration (its keyword, name, or body).
        return leaf.parentsWithSelf
            .filterIsInstance<KtNamedDeclaration>()
            .firstOrNull { it.parent is KtFile }
    }

    private fun computeInternal(): Outcome {
        val declaration = findDeclaration() ?: return Outcome.NotApplicable
        val name = declaration.name ?: return Outcome.NotApplicable
        val declarationFile = declaration.containingFile as? KtFile ?: return Outcome.NotApplicable
        return Outcome.Ready(
            KaMoveDeclarationResult(
                declarationRange = declaration.textRange,
                declarationName = name,
                packageName = declarationFile.packageFqName.asString(),
                suggestedFileName = "$name.kt",
                sourceFilePath = declarationFile.virtualFile?.path ?: "",
            )
        )
    }

    /**
     * Finds every external usage of the declaration at the caret, grouped by containing file —
     * for the NetBeans preview pane. Read-only: uses [KotlinMoveUsageSearchService] directly
     * (the same whole-project search [move] uses internally via
     * [K2MoveDeclarationsRefactoringProcessor.findUsages]) rather than building a full
     * [K2MoveOperationDescriptor] (which would need a real target to already exist).
     *
     * @return `null` if the caret is not on a movable declaration
     */
    fun findUsagesForPreview(): Map<KtFile, List<TextRange>>? {
        val declaration = findDeclaration() ?: return null
        val references = KotlinMoveUsageSearchService.getInstance()?.findUsages(declaration).orEmpty()
        return references
            .mapNotNull { ref -> (ref.element.containingFile as? KtFile)?.let { it to ref.element.textRange } }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Performs the move of the declaration at the caret into [targetKtFile] (already writable —
     * see below) under package [targetPackage], hosted physically at [targetDirectory].
     *
     * Calls the real `K2MoveDeclarationsRefactoringProcessor.performRefactoring()` — IDEA's actual
     * move+retarget algorithm, unmodified. The only adaptation needed is *how the target file is
     * resolved*: IDEA's `K2MoveTargetDescriptor.File` always resolves its target through
     * `PsiDirectory.findFile()`, and in this standalone environment every `PsiDirectory`/
     * `VirtualFile` reached via the real disk filesystem
     * (`org.jetbrains.kotlin.cli.common.localfs.KotlinLocalFileSystem`, extending
     * `CoreLocalFileSystem`) is read-only for PSI mutation — real for reads, but rejects
     * `.add()`/`.delete()`. The only *writable* `KtFile`s in this plugin are the in-memory
     * `LightVirtualFile`-backed ones the analysis session itself manages (obtained via
     * `KotlinAnalysisAPISession.getKtFileForPath` after the physical file is created and the
     * session is refreshed — see `KotlinMoveDeclarationPlugin`). [SessionAwareTargetDirectory]
     * bridges the two: it delegates every `PsiDirectory` member IDEA's conflict-detection code
     * reads (module, FqName, project, ...) to the real, disk-backed [targetDirectory], but
     * overrides `findFile()` to return [targetKtFile] directly — so
     * `K2MoveTargetDescriptor.File.getOrCreateTarget()` finds the already-existing, writable file
     * instead of attempting to create one through the read-only disk directory.
     *
     * @param targetDirectory a real, disk-backed [PsiDirectory] used for conflict-detection
     *                        FqName/module resolution — never mutated directly
     * @param targetKtFile    the already-existing, writable target file
     * @return `null` if the caret is not on a movable declaration
     */
    fun move(targetDirectory: PsiDirectory, targetKtFile: KtFile, targetPackage: FqName): MoveOutcome? {
        val declaration = findDeclaration() ?: return null
        val sourceKtFile = declaration.containingFile as? KtFile ?: return null
        return try {
            val operationDescriptor = K2MoveOperationDescriptor.Declarations(
                project = declaration.project,
                declarations = listOf(declaration),
                baseDir = SessionAwareTargetDirectory(targetDirectory, targetKtFile),
                fileName = targetKtFile.name,
                pkgName = targetPackage,
                searchForText = false,
                searchReferences = true,
                searchInComments = false,
                mppDeclarations = false,
                dirStructureMatchesPkg = false,
                isMoveToExplicitPackage = true,
            )
            val processor = K2MoveDeclarationsRefactoringProcessor(operationDescriptor)
            val usages = processor.findUsages()
            processor.preprocessUsages(Ref(usages))
            if (!processor.conflicts.isEmpty) {
                return MoveOutcome.Conflicts(processor.conflicts.values().toList())
            }

            processor.performRefactoring(usages)

            MoveOutcome.Success(sourceText = sourceKtFile.text, targetText = targetKtFile.text)
        } catch (e: Exception) {
            MoveOutcome.Error(e)
        }
    }

    companion object {
        /**
         * Resolves a real, disk-backed [PsiDirectory] for [directoryPath].
         *
         * The analysis session's open `KtFile`s are backed by in-memory `LightVirtualFile`s (to
         * keep editor content live-synced), which have no directory/VFS hierarchy. Directory
         * *creation/lookup* (what [K2MoveOperationDescriptor.Declarations] needs) instead goes
         * through the platform's disk-backed "file" protocol filesystem, resolved by protocol via
         * [com.intellij.openapi.vfs.VirtualFileManager] rather than
         * `com.intellij.openapi.vfs.LocalFileSystem.getInstance()` directly: this environment
         * registers `org.jetbrains.kotlin.cli.common.localfs.KotlinLocalFileSystem` (extending
         * `CoreLocalFileSystem`, not the real platform `LocalFileSystem` class) under the "file"
         * protocol, so a direct cast to `LocalFileSystem` fails with `ClassCastException`.
         */
        fun resolveDirectory(project: com.intellij.openapi.project.Project, directoryPath: String): PsiDirectory? {
            val absolutePath = java.io.File(directoryPath).absolutePath.replace('\\', '/')
            val vDir = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                .findFileByUrl("file://$absolutePath") ?: return null
            return com.intellij.psi.PsiManager.getInstance(project).findDirectory(vDir)
        }
    }
}

/**
 * Delegates every [PsiDirectory] member to [real] (a disk-backed, read-only-for-writes directory —
 * fine for the metadata IDEA's conflict-detection code reads: module, FqName, project, etc.),
 * except [findFile], which returns [writableTarget] directly instead of attempting a real
 * (unsupported) VFS lookup/creation. See [KaMoveDeclarationComputer.move]'s doc comment.
 */
private class SessionAwareTargetDirectory(
    private val real: PsiDirectory,
    private val writableTarget: KtFile,
) : PsiDirectory by real {
    override fun findFile(name: String): PsiFile? =
        if (name == writableTarget.name) writableTarget else real.findFile(name)
}

/**
 * Data the NetBeans UI needs to prompt for a move target.
 *
 * @param declarationRange  range of the declaration in the source file (preview pane)
 * @param declarationName   simple name of the declaration (e.g. `"Foo"`, `"greet"`)
 * @param packageName       fully-qualified package of the source file (e.g. `"com.example"`)
 * @param suggestedFileName default target file name (e.g. `"Foo.kt"`)
 * @param sourceFilePath    absolute path of the file **actually containing the declaration** —
 *                          may differ from the file the caret was in, when the caret was on a
 *                          usage rather than the declaration itself
 */
data class KaMoveDeclarationResult(
    val declarationRange: com.intellij.openapi.util.TextRange,
    val declarationName: String,
    val packageName: String,
    val sourceFilePath: String,
    val suggestedFileName: String,
)
