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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaPullMembersUpComputer
import io.github.nbplugins.kotlin.refactoring.PullMembersUpRequest
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/** Integrates the K2 Pull Members Up engine with NetBeans refactoring preview, apply, and undo. */
class KotlinPullMembersUpPlugin(
    private val refactoring: KotlinPullMembersUpRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {
    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /** Validates current choices and contributes the atomic source/target apply element. */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val source = ProjectUtils.getFileObjectForDocument(refactoring.document)
            ?: return Problem(true, "Pull Members Up requires a saved Kotlin source file.")
        val project = ProjectUtils.getKotlinProjectForFileObject(source)
            ?: ProjectUtils.getValidProject()
            ?: return Problem(true, "Pull Members Up could not resolve the Kotlin project.")
        val request = refactoring.request()
            ?: return Problem(true, "Select a target supertype and at least one member.")
        KotlinAnalysisAPISession.invalidate(project)
        val session = KotlinAnalysisAPISession.getSession(project)
        val sourcePsi = session.getKtFileForPath(source.path)
            ?: return Problem(true, "Pull Members Up could not resolve the Kotlin source file.")
        val target = org.openide.filesystems.FileUtil.toFileObject(java.io.File(request.targetFilePath))
            ?: return Problem(true, "Pull Members Up could not resolve the target Kotlin file.")
        val targetPsi = session.getKtFileForPath(target.path)
            ?: return Problem(true, "Pull Members Up could not resolve the target Kotlin PSI.")
        return when (val conflicts = KaPullMembersUpComputer(sourcePsi, refactoring.caretOffset, targetPsi).checkConflicts(request)) {
            KaPullMembersUpComputer.ConflictCheck.Clear -> {
                bag.add(refactoring, KotlinPullMembersUpApplyElement(source, project, refactoring.document, request))
                null
            }
            is KaPullMembersUpComputer.ConflictCheck.Conflicts -> Problem(
                true,
                conflicts.items.joinToString("\n") { it.message },
            )
            KaPullMembersUpComputer.ConflictCheck.NotApplicable ->
                Problem(true, "Pull Members Up is no longer applicable at this caret location.")
            is KaPullMembersUpComputer.ConflictCheck.Error -> Problem(true, conflicts.error.message ?: "Pull Members Up failed.")
        }
    }
}

/** Applies and undoes one K2 Pull Members Up change across the source and selected target documents. */
private class KotlinPullMembersUpApplyElement(
    private val sourceFile: FileObject,
    private val project: org.netbeans.api.project.Project,
    private val sourceDocument: StyledDocument,
    private val request: PullMembersUpRequest,
) : SimpleRefactoringElementImplementation() {
    private var sourceSnapshot: String? = null
    private var targetFile: FileObject? = null
    private var targetDocument: StyledDocument? = null
    private var targetSnapshot: String? = null

    override fun getText(): String = "Pull Members Up"
    override fun getDisplayText(): String = text
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)
    override fun getParentFile(): FileObject = sourceFile
    override fun getPosition(): PositionBounds? = null

    /** Refreshes K2 PSI, runs IDEA's move engine, formats both files, and persists atomic document edits. */
    override fun performChange() {
        try {
            KotlinAnalysisAPISession.invalidate(project)
            val session = KotlinAnalysisAPISession.getSession(project)
            val sourcePsi = session.getKtFileForPath(sourceFile.path)
                ?: error("Pull Members Up could not refresh the source Kotlin PSI.")
            val target = org.openide.filesystems.FileUtil.toFileObject(java.io.File(request.targetFilePath))
                ?: error("Pull Members Up could not resolve the target file.")
            val targetPsi = session.getKtFileForPath(target.path)
                ?: error("Pull Members Up could not refresh the target Kotlin PSI.")
            val targetDoc = openDocument(target)
                ?: error("Pull Members Up could not open the target document.")
            sourceSnapshot = sourceDocument.getText(0, sourceDocument.length)
            targetSnapshot = targetDoc.getText(0, targetDoc.length)
            targetFile = target
            targetDocument = targetDoc
            when (val result = KaPullMembersUpComputer(sourcePsi, request.sourceOffset, targetPsi).apply(request)) {
                is KaPullMembersUpComputer.Apply.Success -> {
                    replaceText(sourceDocument, format(result.sourceText, sourceFile.nameExt))
                    replaceText(targetDoc, format(result.targetText, target.nameExt))
                }
                KaPullMembersUpComputer.Apply.NotApplicable -> error("Pull Members Up is no longer applicable.")
                is KaPullMembersUpComputer.Apply.Error -> throw result.error
            }
        } finally {
            KotlinAnalysisAPISession.invalidate(project)
        }
    }

    /** Restores both source files from their pre-refactoring snapshots. */
    override fun undoChange() {
        runCatching {
            sourceSnapshot?.let { replaceText(sourceDocument, it) }
            targetSnapshot?.let { snapshot -> targetDocument?.let { replaceText(it, snapshot) } }
            KotlinAnalysisAPISession.invalidate(project)
        }.onFailure { KotlinLogger.INSTANCE.logException("Undo Pull Members Up failed", it) }
    }

    /** Formats generated K2 text with the active project Kotlin code-style settings. */
    private fun format(text: String, fileName: String): String {
        KotlinFormatterUtils.pushSettings(ProjectCodeStyleStorage.getSettings(project))
        return try {
            KotlinFormatterUtils.formatCode(text, fileName, project, "\n")
        } finally {
            KotlinFormatterUtils.popSettings()
        }
    }

    /** Opens the target file's editor document so it participates in NetBeans undo. */
    private fun openDocument(file: FileObject): StyledDocument? = try {
        DataObject.find(file).lookup.lookup(org.openide.cookies.EditorCookie::class.java)?.openDocument()
    } catch (error: Exception) {
        KotlinLogger.INSTANCE.logException("Pull Members Up could not open ${file.path}", error)
        null
    }

    /** Replaces a document as one user-visible atomic edit. */
    private fun replaceText(document: StyledDocument, text: String) = NbDocument.runAtomicAsUser(document) {
        if (document.length > 0) document.remove(0, document.length)
        document.insertString(0, text, null)
    }
}
