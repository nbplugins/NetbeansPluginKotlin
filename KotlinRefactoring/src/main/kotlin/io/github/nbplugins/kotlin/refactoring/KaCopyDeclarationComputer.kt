/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.usages.K2MoveRenameUsageInfo
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf

/**
 * Headless analysis engine for the **Copy Declaration** refactoring (E9.19).
 *
 * This is a faithful adapter over IDEA's K2 `CopyKotlinDeclarationsHandler`
 * (`kotlin.refactorings.k2`), reusing its real retargeting engine
 * [K2MoveRenameUsageInfo] rather than re-implementing import handling.  IDEA's handler has two
 * code paths, both replicated here:
 *
 *  1. **Sole declaration in file** (`doRefactoringOnFile`): the whole source file is copied,
 *     preserving every import verbatim.  This is handled by the NetBeans apply element using
 *     [KaCopyDeclarationResult.sourceFileText] — no PSI engine call needed.
 *  2. **One of several declarations** (`doRefactoringOnElement`): the declaration is added to the
 *     target file and `retargetUsages(emptyList(), nestedDeclMap, fromCopy = true)` rebinds
 *     internal usages.  This is [copyDeclarationInto].
 *
 * The engine performs no extra import synthesis beyond what IDEA's element path does, so the
 * result is byte-for-byte the behaviour of the IDEA refactoring.
 *
 * @param ktFile       the source file
 * @param caretOffset  caret position within the file
 */
class KaCopyDeclarationComputer(
    private val ktFile: KtFile,
    private val caretOffset: Int,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret is not inside a top-level named declaration. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaCopyDeclarationResult) : Outcome()
    }

    /**
     * Runs the analysis and returns an [Outcome].
     *
     * Returns [Outcome.NotApplicable] when the caret is not inside a top-level [KtNamedDeclaration].
     */
    fun compute(): Outcome = try {
        computeInternal()
    } catch (e: Exception) {
        Outcome.Error(e)
    }

    /**
     * Finds the top-level [KtNamedDeclaration] under the caret (a direct child of the [KtFile]).
     */
    private fun findDeclaration(): KtNamedDeclaration? {
        val leaf = ktFile.findElementAt(caretOffset) ?: return null
        return leaf.parentsWithSelf
            .filterIsInstance<KtNamedDeclaration>()
            .firstOrNull { it.parent is KtFile }
    }

    private fun computeInternal(): Outcome {
        val declaration = findDeclaration() ?: return Outcome.NotApplicable
        val name = declaration.name ?: return Outcome.NotApplicable
        val packageName = ktFile.packageFqName.asString()
        val suggestedFileName = "$name.kt"
        // Sole-declaration-in-file → IDEA copies the whole file (preserving imports).
        val isSole = ktFile.declarations.singleOrNull() === declaration

        return Outcome.Ready(
            KaCopyDeclarationResult(
                declarationRange = declaration.textRange,
                declarationText = declaration.text,
                declarationName = name,
                packageName = packageName,
                suggestedFileName = suggestedFileName,
                isSoleDeclarationInFile = isSole,
                sourceFileText = ktFile.text,
            )
        )
    }

    /**
     * Multi-declaration engine path — faithful port of IDEA
     * `CopyKotlinDeclarationsHandler.doRefactoringOnElement`.
     *
     * Marks the source declaration's internal usages, adds a copy of the declaration to
     * [targetFile], then calls [K2MoveRenameUsageInfo.retargetUsages] with the old→new mapping of
     * nested declarations to rebind internal references (exactly as IDEA does for a from-copy
     * refactoring).
     *
     * **Requirements:** [targetFile] must already belong to the analysis session's source module
     * so that reference resolution and shortening succeed.  Must be called while PSI mutation is
     * permitted (the NetBeans apply element drives this directly, mirroring the Inline refactoring
     * flow).
     *
     * @param targetFile the destination file (already created with the correct package directive)
     * @return the copied [KtNamedDeclaration] inside [targetFile], or `null` if the source
     *         declaration can no longer be resolved
     */
    fun copyDeclarationInto(targetFile: KtFile): KtNamedDeclaration? {
        val declaration = findDeclaration() ?: return null

        // IDEA marks internal usages before the copy so the copyable user-data survives `.copy()`.
        // No document handling is needed here: the `MoveRenameUsageInfo` linkage stub (in Nbm)
        // deliberately performs no document access, so the engine runs over the standalone session's
        // event-free PSI without a live document.
        K2MoveRenameUsageInfo.markInternalUsages(declaration, declaration)

        val copied = targetFile.add(declaration.copy()) as? KtNamedDeclaration ?: return null

        // Map nested declarations (params, locals, …) old→new, mirroring IDEA's mapping built in
        // doRefactoringOnElement. The top-level declaration itself is not included (collectDescendants
        // excludes self) — matching IDEA exactly.
        val oldToNew = HashMap<PsiElement, PsiElement>()
        declaration.collectDescendantsOfType<KtNamedDeclaration>()
            .zip(copied.collectDescendantsOfType<KtNamedDeclaration>())
            .toMap(oldToNew)

        K2MoveRenameUsageInfo.retargetUsages(emptyList(), oldToNew, fromCopy = true)
        return copied
    }

    /**
     * Retargets references inside an already-copied complete source file using IDEA's whole-file
     * Copy Declaration path.
     *
     * @param targetFile destination file whose text is a package-adjusted copy of this computer's source
     * @return final target text ready to be committed through the NetBeans document transaction
     */
    fun retargetCopiedFile(targetFile: KtFile): String {
        K2MoveRenameUsageInfo.retargetInternalUsagesForCopyFile(ktFile, targetFile)
        return targetFile.text
    }
}

/**
 * All data the NetBeans apply element needs to perform Copy Declaration.
 *
 * @param declarationRange         range of the declaration in the source file (preview pane)
 * @param declarationText          full text of the declaration
 * @param declarationName          simple name of the declaration (e.g. `"Foo"`, `"greet"`)
 * @param packageName              fully-qualified package of the source file (e.g. `"com.example"`)
 * @param suggestedFileName        default file name for the copy (e.g. `"Foo.kt"`)
 * @param isSoleDeclarationInFile  `true` when the declaration is the only one in its file; in that
 *                                 case IDEA copies the whole file (imports preserved) and the apply
 *                                 element uses [sourceFileText] directly instead of the engine path
 * @param sourceFileText           the full text of the source file (used by the sole-declaration path)
 */
data class KaCopyDeclarationResult(
    val declarationRange: TextRange,
    val declarationText: String,
    val declarationName: String,
    val packageName: String,
    val suggestedFileName: String,
    val isSoleDeclarationInFile: Boolean,
    val sourceFileText: String,
)
