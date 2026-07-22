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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.ExtractSuperRequest
import io.github.nbplugins.kotlin.refactoring.KaExtractSuperComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/** Shared NetBeans refactoring plugin for Extract Interface and Extract Superclass. */
class KotlinExtractSuperPlugin(
    private val refactoring: KotlinExtractSuperRefactoring,
    private val label: String,
) : ProgressProviderAdapter(), RefactoringPlugin {
    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /** Validates the request against current K2 candidates and adds its multi-file apply operation. */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val source = ProjectUtils.getFileObjectForDocument(refactoring.document)
            ?: return Problem(true, "$label requires a saved Kotlin source file.")
        val project = ProjectUtils.getKotlinProjectForFileObject(source)
            ?: ProjectUtils.getValidProject()
            ?: return Problem(true, "$label could not resolve the Kotlin project.")
        val ktFile = KotlinAnalysisAPISession.getSession(project).getKtFileForPath(source.path)
            ?: return Problem(true, "$label could not resolve the Kotlin source file.")
        return when (val discovery = KaExtractSuperComputer(ktFile, refactoring.caretOffset).discover()) {
            KaExtractSuperComputer.Discovery.NotApplicable -> Problem(true, "$label is not available at this caret location.")
            is KaExtractSuperComputer.Discovery.Error -> Problem(true, discovery.error.message ?: "$label failed")
            is KaExtractSuperComputer.Discovery.Ready -> {
                val targetDirectory = KotlinPackageTarget(project, source)
                    .resolveDirectory(refactoring.targetRootPath, refactoring.targetPackage)
                    ?: return Problem(true, "$label could not resolve the selected target package.")
                bag.add(
                    refactoring,
                    KotlinExtractSuperApplyElement(
                        source,
                        project,
                        targetDirectory,
                        refactoring.document,
                        refactoring.caretOffset,
                        refactoring::request,
                        label,
                    ),
                )
                null
            }
        }
    }
}

/** Applies the real IDEA K2 Extract Super engine to a source file and a newly created Kotlin file. */
private class KotlinExtractSuperApplyElement(
    private val sourceFile: FileObject,
    private val project: org.netbeans.api.project.Project,
    private val targetDirectory: FileObject,
    private val sourceDocument: StyledDocument,
    private val caretOffset: Int,
    private val requestProvider: () -> ExtractSuperRequest?,
    private val label: String,
) : SimpleRefactoringElementImplementation() {
    private var sourceSnapshot: String? = null
    private var targetSnapshot: String? = null
    private var createdTarget: FileObject? = null

    override fun getText(): String = label
    override fun getDisplayText(): String = label
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)
    override fun getParentFile(): FileObject = sourceFile
    override fun getPosition(): PositionBounds? = null

    /** Creates the destination file, refreshes K2 PSI, and persists both IDEA-mutated files. */
    override fun performChange() {
        val request = requestProvider()
            ?: throw IllegalStateException("$label has no validated request.")
        val targetFileName = request.targetFileName.ensureKotlinExtension()
        val preexistingTarget = targetDirectory.getFileObject(targetFileName)
        val targetFile = preexistingTarget ?: targetDirectory.createData(targetFileName).also { createdTarget = it }
        try {
            if (preexistingTarget == null) {
                targetFile.getOutputStream().use { output -> output.write(packageHeader(request.targetPackage).toByteArray()) }
            }
            targetSnapshot = preexistingTarget?.asText()
            sourceSnapshot = sourceDocument.getText(0, sourceDocument.length)

            KotlinAnalysisAPISession.invalidate(project)
            val session = KotlinAnalysisAPISession.getSession(project)
            val sourceKtFile = session.getKtFileForPath(sourceFile.path)
                ?: throw IllegalStateException("$label could not refresh the source Kotlin PSI.")
            val targetKtFile = session.getKtFileForPath(targetFile.path)
                ?: throw IllegalStateException("$label could not refresh the target Kotlin PSI.")
            when (val result = KaExtractSuperComputer(sourceKtFile, caretOffset)
                .apply(request.copy(targetFileName = targetFileName), targetKtFile)) {
                is KaExtractSuperComputer.Apply.Success -> {
                    replaceText(sourceDocument, result.sourceText)
                    targetFile.getOutputStream().use { output -> output.write(result.targetText.toByteArray()) }
                    KotlinAnalysisAPISession.invalidate(project)
                }
                is KaExtractSuperComputer.Apply.Error -> throw result.error
                KaExtractSuperComputer.Apply.NotApplicable ->
                    throw IllegalStateException("$label is no longer applicable at this caret location.")
            }
        } catch (error: Throwable) {
            if (preexistingTarget == null && targetFile.isValid) targetFile.delete()
            throw error
        }
    }

    /** Restores both documents or removes the file that this operation created. */
    override fun undoChange() {
        runCatching {
            sourceSnapshot?.let { replaceText(sourceDocument, it) }
            val target = createdTarget
            if (target != null && target.isValid) target.delete()
            else targetSnapshot?.let { original ->
                val request = requestProvider() ?: return@let
                targetDirectory.getFileObject(request.targetFileName.ensureKotlinExtension())?.getOutputStream()?.use {
                    it.write(original.toByteArray())
                }
            }
            KotlinAnalysisAPISession.invalidate(project)
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("Undo $label failed", error) }
    }

    /** Replaces a document atomically so the source editor observes one coherent change. */
    private fun replaceText(document: StyledDocument, text: String) = NbDocument.runAtomicAsUser(document) {
        if (document.length > 0) document.remove(0, document.length)
        document.insertString(0, text, null)
    }

    /** Creates the selected package directive when seeding a destination Kotlin file. */
    private fun packageHeader(packageName: String): String = packageName.trim()
        .takeIf(String::isNotEmpty)
        ?.let { name -> "package $name\n\n" }
        .orEmpty()

    /** Ensures the supplied target name is a Kotlin source filename. */
    private fun String.ensureKotlinExtension(): String = if (endsWith(".kt")) this else "$this.kt"
}
