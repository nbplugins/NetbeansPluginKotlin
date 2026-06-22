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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.navigation.KotlinFindUsagesResultElement
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.api.SafeDeleteRefactoring
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.PositionBounds
import org.openide.text.PositionRef
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.BadLocationException
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that handles [SafeDeleteRefactoring] for Kotlin symbols.
 *
 * When the user invokes Safe Delete on a Kotlin symbol in the editor, [prepare] is called:
 * 1. Resolves the symbol at the cursor using [KaSafeDeleteComputer].
 * 2. Adds a [KotlinFindUsagesResultElement] for each reference found (read-only, shown in preview).
 * 3. Adds a [KotlinSafeDeleteElement] that performs the actual deletion on confirm.
 * 4. If usages exist, returns a non-fatal [Problem] so the user can decide whether to proceed.
 *
 * The lookup in [refactoring]'s source is expected to contain a [StyledDocument] and an [Int]
 * caret offset, set by [KotlinActionsImplementationProvider.doDelete].
 *
 * @param refactoring the [SafeDeleteRefactoring] created by [KotlinActionsImplementationProvider]
 */
class KotlinSafeDeletePlugin(private val refactoring: SafeDeleteRefactoring) :
    ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Finds all usages of the symbol at the cursor and populates [bag] with:
     * - One [KotlinFindUsagesResultElement] per usage (read-only preview items).
     * - One [KotlinSafeDeleteElement] that performs the deletion.
     *
     * @return `null` if the symbol has no usages, a non-fatal [Problem] if usages exist
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.refactoringSource.lookup(StyledDocument::class.java)
            ?: return null
        val fo = ProjectUtils.getFileObjectForDocument(doc)
            ?: return null
        val offset = refactoring.refactoringSource.lookup(Int::class.javaObjectType)
            ?: return null

        val project = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null
        val session = KotlinAnalysisAPISession.getSession(project)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null
        val allFiles = KotlinPsiManager.getFilesByProject(project)

        val result = KaSafeDeleteComputer(ktFile, offset, session, allFiles).compute()
            ?: return null

        // Add found usage items (shown in preview panel, read-only).
        for ((usageFile, ranges) in result.usages) {
            for (range in ranges) {
                runCatching {
                    bag.add(refactoring, KotlinFindUsagesResultElement(range, usageFile))
                }
            }
        }

        // Add the deletion element (performs the actual removal on confirm).
        val declarationFo = FileUtil.toFileObject(
            FileUtil.normalizeFile(java.io.File(result.declarationFilePath))
        ) ?: fo
        bag.add(refactoring, KotlinSafeDeleteElement(declarationFo, result))

        // Return a non-fatal problem if there are usages so the user can confirm.
        val usageCount = result.usages.values.sumOf { it.size }
        return if (usageCount > 0) {
            val files = result.usages.size
            Problem(
                false,
                "'${result.declarationName}' is used in $usageCount place(s) across $files file(s). Delete anyway?"
            )
        } else null
    }
}

/**
 * A [SimpleRefactoringElementImplementation] that deletes a Kotlin declaration.
 *
 * [performChange] replaces the declaration's text range with an empty string.
 * [undoChange] re-inserts the original text.
 *
 * @param parentFile the file containing the declaration
 * @param result     the [KaSafeDeleteResult] describing the range and name to delete
 */
class KotlinSafeDeleteElement(
    private val parentFile: FileObject,
    private val result: KaSafeDeleteResult,
) : SimpleRefactoringElementImplementation() {

    private var deletedText: String = ""

    override fun getText(): String = "Delete '${result.declarationName}'"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(parentFile)
    override fun getParentFile(): FileObject = parentFile

    override fun getPosition(): PositionBounds? = try {
        val dob = DataObject.find(parentFile)
        val ces = dob.lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        val start: PositionRef = ces.createPositionRef(result.declarationStart, Bias.Forward)
        val end: PositionRef = ces.createPositionRef(result.declarationEnd, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    override fun performChange() {
        try {
            val dob = DataObject.find(parentFile)
            val ec = dob.lookup.lookup(org.openide.cookies.EditorCookie::class.java) ?: return
            val doc = ec.openDocument() ?: return
            val len = result.declarationEnd - result.declarationStart
            deletedText = doc.getText(result.declarationStart, len)
            doc.remove(result.declarationStart, len)
        } catch (_: BadLocationException) {
        } catch (_: Exception) {}
    }

    override fun undoChange() {
        try {
            val dob = DataObject.find(parentFile)
            val ec = dob.lookup.lookup(org.openide.cookies.EditorCookie::class.java) ?: return
            val doc = ec.openDocument() ?: return
            doc.insertString(result.declarationStart, deletedText, null)
        } catch (_: BadLocationException) {
        } catch (_: Exception) {}
    }
}
