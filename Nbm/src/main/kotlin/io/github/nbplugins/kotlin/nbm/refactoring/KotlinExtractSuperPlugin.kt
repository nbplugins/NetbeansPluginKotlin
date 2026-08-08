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
import io.github.nbplugins.kotlin.refactoring.ExtractSuperRequest
import io.github.nbplugins.kotlin.refactoring.KaExtractSuperComputer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups

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
        KotlinLogger.INSTANCE.logInfo(
            "$label: prepare caret=${refactoring.caretOffset}, classOffset=${refactoring.classOffset}, " +
                "root=${refactoring.targetRootPath}, package=${refactoring.targetPackage}, file=${refactoring.targetFileName}",
        )
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
                KotlinLogger.INSTANCE.logInfo(
                    "$label: prepare ready class=${discovery.sourceName}, members=${discovery.members.size}, " +
                        "targetDirectory=${targetDirectory.path}",
                )
                bag.add(
                    refactoring,
                    KotlinExtractSuperApplyElement(
                        source,
                        project,
                        targetDirectory,
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

/**
 * Formats one generated Extract Super file with the selected NetBeans Kotlin code-style settings.
 *
 * @param text raw text produced by the copied IDEA K2 engine.
 * @param fileName Kotlin file name used by the formatter PSI factory.
 * @param project project whose Kotlin formatting settings apply.
 * @return formatted Kotlin source text ready to persist.
 */
internal fun formatExtractSuperText(
    text: String,
    fileName: String,
    project: org.netbeans.api.project.Project,
): String {
    KotlinFormatterUtils.pushSettings(ProjectCodeStyleStorage.getSettings(project))
    return try {
        KotlinFormatterUtils.formatCode(text, fileName, project, "\n")
    } finally {
        KotlinFormatterUtils.popSettings()
    }
}

/**
 * Logs syntax diagnostics for the target text immediately before it is persisted.
 *
 * @param label name of the Extract Super operation being applied.
 * @param text formatted target Kotlin source text.
 * @param project NetBeans project supplying the Kotlin PSI factory.
 */
private fun logTargetSyntaxErrors(label: String, text: String, project: org.netbeans.api.project.Project) {
    val target = KotlinFormatterUtils.createPsiFactory(project).createFile("ExtractSuperTarget.kt", text)
    val errors = PsiTreeUtil.collectElementsOfType(target, PsiErrorElement::class.java)
    if (errors.isEmpty()) {
        KotlinLogger.INSTANCE.logInfo("$label: formatted target has no syntax errors")
    } else {
        KotlinLogger.INSTANCE.logInfo(
            "$label: formatted target syntax errors=" + errors.joinToString { error ->
                "${error.errorDescription} at ${error.textOffset}"
            },
        )
    }
}

/** Applies the real IDEA K2 Extract Super engine to a source file and a newly created Kotlin file. */
private class KotlinExtractSuperApplyElement(
    private val sourceFile: FileObject,
    private val project: org.netbeans.api.project.Project,
    private val targetDirectory: FileObject,
    private val caretOffset: Int,
    private val requestProvider: () -> ExtractSuperRequest?,
    private val label: String,
) : SimpleRefactoringElementImplementation() {
    /** Successfully committed transaction, retained for Undo Last Refactoring. */
    private var transaction: KotlinRefactoringTransaction? = null

    override fun getText(): String = label
    override fun getDisplayText(): String = label
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)
    override fun getParentFile(): FileObject = sourceFile
    override fun getPosition(): PositionBounds? = null

    /** Creates or captures the destination, runs K2, then commits source and target together. */
    override fun performChange() {
        var pendingTransaction: KotlinRefactoringTransaction? = null
        var targetPath = "<unresolved>"
        try {
            val request = requestProvider()
                ?: throw IllegalStateException("$label has no validated request.")
            val targetFileName = request.targetFileName.ensureKotlinExtension()
            KotlinLogger.INSTANCE.logInfo(
                "$label: apply start source=${sourceFile.path}, targetDirectory=${targetDirectory.path}, " +
                    "targetFile=$targetFileName, package=${request.targetPackage}, caret=$caretOffset, " +
                    "classOffset=${request.classOffset}, selected=${request.selectedOffsets}",
            )
            val currentTransaction = KotlinRefactoringTransaction()
            pendingTransaction = currentTransaction
            currentTransaction.captureExisting(sourceFile)
            val preexistingTarget = targetDirectory.getFileObject(targetFileName)
            val targetFile = preexistingTarget?.also(currentTransaction::captureExisting)
                ?: currentTransaction.createFile(targetDirectory, targetFileName, packageHeader(request.targetPackage))
            targetPath = targetFile.path
            KotlinLogger.INSTANCE.logInfo(
                "$label: target created=${preexistingTarget == null}, path=${targetFile.path}, valid=${targetFile.isValid}",
            )

            KotlinAnalysisAPISession.invalidate(project)
            val session = KotlinAnalysisAPISession.getSession(project)
            val sourceKtFile = session.getKtFileForPath(sourceFile.path)
                ?: throw IllegalStateException("$label could not refresh the source Kotlin PSI.")
            val targetKtFile = session.getKtFileForPath(targetFile.path)
                ?: throw IllegalStateException("$label could not refresh the target Kotlin PSI.")
            when (val result = KaExtractSuperComputer(sourceKtFile, caretOffset)
                .apply(request.copy(targetFileName = targetFileName), targetKtFile)) {
                is KaExtractSuperComputer.Apply.Success -> {
                    val formattedSource = formatExtractSuperText(result.sourceText, sourceFile.nameExt, project)
                    val formattedTarget = formatExtractSuperText(result.targetText, targetFile.nameExt, project)
                    logTargetSyntaxErrors(label, formattedTarget, project)
                    currentTransaction.stageText(sourceFile, formattedSource)
                    currentTransaction.stageText(targetFile, formattedTarget)
                    currentTransaction.commit()
                    transaction = currentTransaction
                    pendingTransaction = null
                    KotlinLogger.INSTANCE.logInfo("$label: apply complete targetExists=${targetFile.isValid}")
                }
                is KaExtractSuperComputer.Apply.Error -> throw result.error
                KaExtractSuperComputer.Apply.NotApplicable ->
                    throw IllegalStateException("$label is no longer applicable at this caret location.")
            }
        } catch (error: Throwable) {
            KotlinLogger.INSTANCE.logException("$label: apply failed; target=$targetPath", error)
        } finally {
            runCatching { pendingTransaction?.rollback() }
                .onFailure { KotlinLogger.INSTANCE.logException("$label: rollback failed", it) }
            KotlinAnalysisAPISession.invalidate(project)
        }
    }

    /** Restores both source and target through the successful transaction snapshots. */
    override fun undoChange() {
        runCatching {
            transaction?.undo()
            transaction = null
            KotlinAnalysisAPISession.invalidate(project)
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("Undo $label failed", error) }
    }

    /** Creates the selected package directive when seeding a destination Kotlin file. */
    private fun packageHeader(packageName: String): String = packageName.trim()
        .takeIf(String::isNotEmpty)
        ?.let { name -> "package $name\n\n" }
        .orEmpty()

    /** Ensures the supplied target name is a Kotlin source filename. */
    private fun String.ensureKotlinExtension(): String = if (endsWith(".kt")) this else "$this.kt"
}
