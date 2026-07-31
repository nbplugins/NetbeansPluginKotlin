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

import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaPushMembersDownComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import java.io.File
import javax.swing.text.StyledDocument

/** Integrates the IDEA K2 Push Members Down processor with NetBeans preview, apply, and undo. */
class KotlinPushMembersDownPlugin(
    private val refactoring: KotlinPushMembersDownRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {
    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /** Validates selections and contributes the multi-document atomic apply element. */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val source = ProjectUtils.getFileObjectForDocument(refactoring.document)
            ?: return Problem(true, "Push Members Down requires a saved Kotlin source file.")
        val project = ProjectUtils.getKotlinProjectForFileObject(source)
            ?: ProjectUtils.getValidProject()
            ?: return Problem(true, "Push Members Down could not resolve the Kotlin project.")
        if (!refactoring.isReady()) return Problem(true, "Select at least one member to push down.")
        KotlinAnalysisAPISession.invalidate(project)
        val session = KotlinAnalysisAPISession.getBuildScopedSession(project)
        val sourcePsi = session.getKtFileForPath(source.path)
            ?: return Problem(true, "Push Members Down could not resolve the Kotlin source file.")
        val discovery = KaPushMembersDownComputer(sourcePsi, refactoring.sourceOffset).discover()
        if (discovery !is KaPushMembersDownComputer.Discovery.Ready) {
            return Problem(true, "Push Members Down is no longer applicable at this caret location.")
        }
        bag.add(refactoring, KotlinPushMembersDownApplyElement(source, project, refactoring.document, refactoring))
        return null
    }
}

/** Applies and restores an IDEA Push Down mutation across every changed build-session document. */
private class KotlinPushMembersDownApplyElement(
    private val sourceFile: FileObject,
    private val project: org.netbeans.api.project.Project,
    private val sourceDocument: StyledDocument,
    private val refactoring: KotlinPushMembersDownRefactoring,
) : SimpleRefactoringElementImplementation() {
    private val snapshots = linkedMapOf<StyledDocument, String>()

    override fun getText(): String = "Push Members Down"
    override fun getDisplayText(): String = text
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)
    override fun getParentFile(): FileObject = sourceFile
    override fun getPosition(): PositionBounds? = null

    /** Runs the copied IDEA lifecycle and persists each build-session document changed by it. */
    override fun performChange() {
        try {
            KotlinAnalysisAPISession.invalidate(project)
            val session = KotlinAnalysisAPISession.getBuildScopedSession(project)
            val sourcePsi = session.getKtFileForPath(sourceFile.path)
                ?: error("Push Members Down could not refresh the Kotlin source PSI.")
            val affected = session.session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
                .mapNotNull { psi -> psi.virtualFile?.path?.let { path -> FileUtil.toFileObject(File(path))?.let { it to psi } } }
            val documents = affected.mapNotNull { (file, psi) -> openDocument(file)?.let { document -> Triple(file, psi, document) } }
            documents.forEach { (_, _, document) -> snapshots[document] = document.getText(0, document.length) }
            if (sourceDocument !in snapshots) snapshots[sourceDocument] = sourceDocument.getText(0, sourceDocument.length)

            when (val result = KaPushMembersDownComputer(sourcePsi, refactoring.sourceOffset).apply(
                refactoring.selectedOffsets,
                refactoring.abstractOffsets,
            )) {
                KaPushMembersDownComputer.Apply.Success -> documents.forEach { (file, psi, document) ->
                    val before = snapshots[document] ?: return@forEach
                    if (before != psi.text) replaceText(document, format(psi.text, file.nameExt))
                }
                KaPushMembersDownComputer.Apply.NotApplicable -> error("Push Members Down is no longer applicable.")
                is KaPushMembersDownComputer.Apply.Error -> throw result.error
            }
        } finally {
            KotlinAnalysisAPISession.invalidate(project)
        }
    }

    /** Restores every edited source or sibling-module document from its pre-refactoring snapshot. */
    override fun undoChange() {
        runCatching {
            snapshots.forEach { (document, text) -> replaceText(document, text) }
            KotlinAnalysisAPISession.invalidate(project)
        }.onFailure { KotlinLogger.INSTANCE.logException("Undo Push Members Down failed", it) }
    }

    /** Formats copied IDEA output with the active project Kotlin style. */
    private fun format(text: String, fileName: String): String {
        KotlinFormatterUtils.pushSettings(ProjectCodeStyleStorage.getSettings(project))
        return try {
            KotlinFormatterUtils.formatCode(text, fileName, project, "\n")
        } finally {
            KotlinFormatterUtils.popSettings()
        }
    }

    /** Opens a document so NetBeans tracks it in the refactoring undo transaction. */
    private fun openDocument(file: FileObject): StyledDocument? = try {
        DataObject.find(file).lookup.lookup(org.openide.cookies.EditorCookie::class.java)?.openDocument()
    } catch (error: Exception) {
        KotlinLogger.INSTANCE.logException("Push Members Down could not open ${file.path}", error)
        null
    }

    /** Replaces a document as one visible user edit. */
    private fun replaceText(document: StyledDocument, text: String) = NbDocument.runAtomicAsUser(document) {
        if (document.length > 0) document.remove(0, document.length)
        document.insertString(0, text, null)
    }
}
