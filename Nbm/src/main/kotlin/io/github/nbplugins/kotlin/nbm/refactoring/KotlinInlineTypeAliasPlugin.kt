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
import io.github.nbplugins.kotlin.refactoring.KaInlineTypeAliasComputer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.Position.Bias

/**
 * Bridges NetBeans refactoring UI to IDEA's K2 **Inline Type Alias** strategy.
 *
 * @param refactoring document, caret, and UI options chosen by the user
 */
class KotlinInlineTypeAliasPlugin(
    private val refactoring: KotlinInlineTypeAliasRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Builds portable Kotlin preview items and one transactional apply element.
     *
     * @param bag NetBeans element bag to populate
     * @return fatal validation problem, or `null` when a valid plan was prepared
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val sourceFile = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(sourceFile) ?: return null
        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(sourceFile.path) ?: return null
        val projectKtFiles = session.session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()

        return when (val outcome = KaInlineTypeAliasComputer(ktFile, refactoring.offset, projectKtFiles).compute()) {
            is KaInlineTypeAliasComputer.Outcome.NotApplicable -> null
            is KaInlineTypeAliasComputer.Outcome.Error -> Problem(true, outcome.error.message)
            is KaInlineTypeAliasComputer.Outcome.Ready -> {
                val selectedUsages = selectedUsages(outcome.result.usages, outcome.result.triggerUsage)
                if (refactoring.inlineThisOnly && selectedUsages.isEmpty()) {
                    return Problem(true, "Cannot inline this occurrence: the type alias usage is no longer available.")
                }
                selectedUsages.forEach { (usageFile, usages) ->
                    val usageFo = fileObjectFor(usageFile) ?: return@forEach
                    usages.forEach { usage ->
                        runCatching {
                            bag.add(
                                refactoring,
                                KotlinFindUsagesResultElement(
                                    OffsetRange(usage.textRange.startOffset, usage.textRange.endOffset),
                                    usageFo,
                                ),
                            )
                        }
                    }
                }
                val declarationFile = fileObjectFor(outcome.result.typeAlias.containingKtFile) ?: sourceFile
                bag.add(
                    refactoring,
                    KotlinInlineTypeAliasApplyElement(declarationFile, nbProject, sourceFile.path, refactoring),
                )
                null
            }
        }
    }

    /** Limits the plan to its trigger reference only when the UI chose that mode. */
    private fun selectedUsages(
        allUsages: Map<KtFile, List<KtReferenceExpression>>,
        triggerUsage: KtReferenceExpression?,
    ): Map<KtFile, List<KtReferenceExpression>> {
        if (!refactoring.inlineThisOnly) return allUsages
        val trigger = triggerUsage ?: return emptyMap()
        val file = trigger.containingKtFile
        return mapOf(file to listOf(trigger))
    }

    /** Converts a session virtual path to a writable NetBeans file. */
    private fun fileObjectFor(file: KtFile): FileObject? =
        file.virtualFile?.path?.let { FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(it))) }
}

/**
 * Performs an Inline Type Alias change as a hunk-preserving NetBeans transaction.
 *
 * @param declarationFile parent file used by the preview hierarchy
 * @param nbProject NetBeans project owning the standalone K2 session
 * @param cursorFilePath source file path at action invocation
 * @param refactoring carrier with mode options
 */
class KotlinInlineTypeAliasApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val cursorFilePath: String,
    private val refactoring: KotlinInlineTypeAliasRefactoring,
) : SimpleRefactoringElementImplementation() {

    private var transaction: KotlinRefactoringTransaction? = null

    override fun getText(): String = "Inline type alias"
    override fun getDisplayText(): String = text
    override fun getLookup(): Lookup = Lookups.fixed(declarationFile)
    override fun getParentFile(): FileObject = declarationFile

    override fun getPosition(): PositionBounds? = try {
        val support = DataObject.find(declarationFile).lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        PositionBounds(support.createPositionRef(0, Bias.Forward), support.createPositionRef(0, Bias.Backward))
    } catch (_: Exception) {
        null
    }

    /** Recomputes and executes the IDEA strategy, committing all generated document text together. */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        try {
            val session = KotlinAnalysisAPISession.getSession(nbProject)
            val cursorKtFile = session.getKtFileForPath(cursorFilePath) ?: return
            val projectKtFiles = session.session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
            val ready = KaInlineTypeAliasComputer(cursorKtFile, refactoring.offset, projectKtFiles).compute()
                as? KaInlineTypeAliasComputer.Outcome.Ready ?: return
            val result = ready.result
            val usages = chooseUsages(result.usages, result.triggerUsage)
            if (refactoring.inlineThisOnly && usages.isEmpty()) return

            val affectedKtFiles = linkedSetOf<KtFile>()
            affectedKtFiles += usages.keys
            if (!refactoring.inlineThisOnly && !refactoring.keepDeclaration) {
                affectedKtFiles += result.typeAlias.containingKtFile
            }
            val affectedFiles = affectedKtFiles.mapNotNull { ktFile ->
                fileObjectFor(ktFile)?.let { file -> ktFile to file }
            }.toMap(linkedMapOf())
            if (affectedFiles.size != affectedKtFiles.size) return

            val currentTransaction = KotlinRefactoringTransaction()
            pending = currentTransaction
            affectedFiles.values.forEach(currentTransaction::captureExisting)

            for ((_, references) in usages) {
                for (usage in references.sortedForInline()) {
                    if (!usage.isValid) continue
                    result.strategy.createReplacer(usage)?.invoke()
                }
            }
            if (!refactoring.inlineThisOnly && !refactoring.keepDeclaration) result.typeAlias.delete()

            affectedKtFiles.forEach { ktFile ->
                val file = affectedFiles.getValue(ktFile)
                currentTransaction.stageHunkText(file, ktFile.text, nbProject)
            }
            currentTransaction.commit()
            transaction = currentTransaction
            pending = null
            KotlinAnalysisAPISession.invalidate(nbProject)
        } catch (_: Throwable) {
            runCatching { pending?.rollback() }
            KotlinAnalysisAPISession.invalidate(nbProject)
        }
    }

    /** Restores all edited files and invalidates cached standalone PSI. */
    override fun undoChange() {
        runCatching { transaction?.undo() }
        KotlinAnalysisAPISession.invalidate(nbProject)
    }

    /** Applies the UI's all-usages/this-occurrence selection to fresh K2 references. */
    private fun chooseUsages(
        allUsages: Map<KtFile, List<KtReferenceExpression>>,
        triggerUsage: KtReferenceExpression?,
    ): Map<KtFile, List<KtReferenceExpression>> {
        if (!refactoring.inlineThisOnly) return allUsages
        val trigger = triggerUsage ?: return emptyMap()
        return mapOf(trigger.containingKtFile to listOf(trigger))
    }

    /** Resolves a K2 file path to its NetBeans file counterpart. */
    private fun fileObjectFor(file: KtFile): FileObject? =
        file.virtualFile?.path?.let { FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(it))) }

    /** Orders nested references exactly as IDEA's `UsageReplacementStrategy` does. */
    private fun List<KtReferenceExpression>.sortedForInline(): List<KtReferenceExpression> =
        sortedWith { first, second ->
            if (first.parent.textRange.intersects(second.parent.textRange)) {
                compareValuesBy(second, first) { it.startOffset }
            } else {
                compareValuesBy(first, second) { it.startOffset }
            }
        }
}
