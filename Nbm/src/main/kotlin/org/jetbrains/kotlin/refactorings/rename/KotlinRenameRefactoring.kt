/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.refactorings.rename

import io.github.nbplugins.kotlin.refactoring.KaRenameComputer
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import javax.swing.text.StyledDocument
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.api.RenameRefactoring
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import java.io.File
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.openide.filesystems.FileUtil

/**
 * [RefactoringPlugin] that performs Kotlin rename refactoring using the K2 Analysis API.
 *
 * Triggered by Alt+Shift+R on a Kotlin identifier. The source lookup must contain:
 * - [StyledDocument] — the active editor document
 * - [Integer] — caret offset within the document (do NOT include [FileObject] — a built-in
 *   NetBeans plugin would rename the file on disk instead of the code symbol)
 *
 * This class:
 * 1. Extracts the K2 session for the project containing the cursor file.
 * 2. Delegates to [KaRenameComputer] to find all TextChanges across project files.
 * 3. Wraps each file's changes in a [KotlinFileRenameElement] and adds it to [bag].
 *
 * Override cascade is handled transparently by [KaRenameComputer]: renaming a function also
 * renames all its overrides and super-implementations.
 *
 * @param refactoring the [RenameRefactoring] created by [KotlinActionsImplementationProvider]
 */
class KotlinRenameRefactoring(val refactoring: RenameRefactoring) :
    ProgressProviderAdapter(), RefactoringPlugin {

    override fun checkParameters(): Problem? = null

    override fun preCheck(): Problem? {
        // Verify that a .kt file with a valid caret offset is in the lookup
        val doc = refactoring.refactoringSource.lookup(StyledDocument::class.java) ?: return Problem(true, "")
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return Problem(true, "")
        return if (fo.hasExt("kt")) null else Problem(true, "")
    }

    override fun fastCheckParameters(): Problem? = null

    override fun cancelRequest() {}

    /**
     * Resolves all rename changes using K2 and adds a [KotlinRefactoringElement] to [bag]
     * for each occurrence (declaration name + all reference sites across project files).
     *
     * @param bag the bag to receive the rename elements
     * @return `null` on success, or a [Problem] if the session or symbol cannot be resolved
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.refactoringSource.lookup(StyledDocument::class.java)
            ?: return null
        val fo = ProjectUtils.getFileObjectForDocument(doc)
            ?: return null
        val offset = refactoring.refactoringSource.lookup(Int::class.javaObjectType)
            ?: return null
        val newName = refactoring.newName
            ?: return null

        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: return null
        val session = KotlinAnalysisAPISession.getSession(project)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null
        val allFiles = KotlinPsiManager.getFilesByProject(project)
        val allKtFiles = allFiles.mapNotNull { session.getKtFileForPath(it.path) }.toSet()

        val computer = KaRenameComputer(ktFile, offset, allKtFiles, newName, refactoring.isSearchInComments)

        // No-op if name is unchanged (avoids empty undo entries and spurious saves).
        val oldSymbolName = computer.resolveOldName()
        if (oldSymbolName == null || oldSymbolName == newName) return null

        val changes = computer.compute() ?: return null

        val foByPath = allFiles.associateBy { it.path }
        for ((path, textChanges) in changes) {
            // Symbols overridden in another Maven module (or otherwise outside
            // KotlinPsiManager.getFilesByProject) still need to be renamed — resolve
            // the FileObject directly from the path when the project map misses it.
            val fileObject = foByPath[path]
                ?: FileUtil.toFileObject(FileUtil.normalizeFile(File(path)))
                ?: continue
            bag.add(refactoring, KotlinFileRenameElement(fileObject, textChanges))
        }

        // If the renamed symbol is a class whose name matches the containing file's base name,
        // also rename the file to match the new class name (one-class-per-file convention).
        // Must be added AFTER content elements so content edits apply before the file rename.
        if (fo.name == oldSymbolName) {
            bag.add(refactoring, KotlinFileObjectRenameElement(fo, oldSymbolName, newName))
        }

        return null
    }
}
