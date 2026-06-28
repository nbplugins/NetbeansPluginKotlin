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
import io.github.nbplugins.kotlin.nbm.navigation.moveCaretToOffset
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaIntroduceImportAliasComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import org.openide.windows.TopComponent
import javax.swing.SwingUtilities
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that implements the Kotlin **Introduce Import Alias** refactoring.
 *
 * Bridges the NetBeans refactoring framework to [KaIntroduceImportAliasComputer]:
 *  1. `prepare()` finds the import directive at the caret and adds occurrence elements and an
 *     apply element to [bag].
 *  2. If the caret is not on a suitable import, `prepare()` returns `null` (no problem, no elements).
 *
 * @param refactoring the carrier [KotlinIntroduceImportAliasRefactoring]
 */
class KotlinIntroduceImportAliasPlugin(
    private val refactoring: KotlinIntroduceImportAliasRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the import directive at the caret and populates [bag] with:
     *  - read-only [KotlinFindUsagesResultElement]s per usage (for the preview pane),
     *  - one [KotlinIntroduceImportAliasApplyElement] that performs the text transformation.
     *
     * @return `null` on success or if not applicable; a fatal [Problem] on analysis error
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaIntroduceImportAliasComputer(ktFile, refactoring.caretOffset)
        return when (val outcome = computer.compute()) {
            is KaIntroduceImportAliasComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinIntroduceImportAliasPlugin.prepare: NotApplicable at offset " +
                            "${refactoring.caretOffset} in ${fo.path}"
                )
                null
            }
            is KaIntroduceImportAliasComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException(
                    "KotlinIntroduceImportAliasPlugin.prepare: Error", outcome.error
                )
                Problem(true, outcome.error.message ?: "Introduce Import Alias failed")
            }
            is KaIntroduceImportAliasComputer.Outcome.Ready -> {
                val result = outcome.result

                for (range in result.usageRanges) {
                    runCatching {
                        bag.add(
                            refactoring,
                            KotlinFindUsagesResultElement(OffsetRange(range.startOffset, range.endOffset), fo),
                        )
                    }
                }

                bag.add(refactoring, KotlinIntroduceImportAliasApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing element that performs the introduce-import-alias transformation.
 *
 * Strategy:
 *  1. Re-runs [KaIntroduceImportAliasComputer] to get a fresh result.
 *  2. Replaces all usage occurrences of the short name with the chosen alias (back-to-front).
 *  3. Replaces the import directive with `import FQN as chosenAlias`.
 *
 * @param declarationFile  the file being refactored
 * @param nbProject        the NetBeans project (for K2 session access and invalidation)
 * @param refactoring      the carrier holding [KotlinIntroduceImportAliasRefactoring.chosenAlias]
 */
class KotlinIntroduceImportAliasApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinIntroduceImportAliasRefactoring,
) : SimpleRefactoringElementImplementation() {

    private var snapshot: String? = null

    override fun getText(): String = "Introduce import alias"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(declarationFile)
    override fun getParentFile(): FileObject = declarationFile

    override fun getPosition(): PositionBounds? = try {
        val dob = DataObject.find(declarationFile)
        val ces = dob.lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        val start = ces.createPositionRef(0, Bias.Forward)
        val end = ces.createPositionRef(0, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    override fun performChange() {
        val activeBefore: TopComponent? = runCatching { TopComponent.getRegistry().activated }.getOrNull()
        try {
            runCatching {
                val fo = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: run {
                    KotlinLogger.INSTANCE.logWarning("KotlinIntroduceImportAliasApplyElement: no FileObject for doc")
                    return@runCatching
                }
                val nbProject2 = ProjectUtils.getKotlinProjectForFileObject(fo) ?: run {
                    KotlinLogger.INSTANCE.logWarning("KotlinIntroduceImportAliasApplyElement: no project for ${fo.path}")
                    return@runCatching
                }

                val session = KotlinAnalysisAPISession.getSession(nbProject2)
                val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching

                val computer = KaIntroduceImportAliasComputer(ktFile, refactoring.caretOffset)
                val outcome2 = computer.compute()
                val ready = outcome2 as? KaIntroduceImportAliasComputer.Outcome.Ready ?: run {
                    KotlinLogger.INSTANCE.logWarning(
                        "KotlinIntroduceImportAliasApplyElement.performChange: compute() returned $outcome2"
                    )
                    return@runCatching
                }
                val result = ready.result

                val doc = openDocument(fo) ?: return@runCatching
                val originalText = doc.getText(0, doc.length)
                snapshot = originalText

                val alias = refactoring.chosenAlias.ifBlank { result.shortName }
                val importRange = result.importDirectiveRange

                // 1. Replace usages back-to-front (usages come after the import, so offsets are stable).
                var newText = originalText
                val sortedUsages = result.usageRanges.sortedByDescending { it.startOffset }
                for (range in sortedUsages) {
                    newText = newText.substring(0, range.startOffset) +
                            alias +
                            newText.substring(range.endOffset)
                }

                // 2. Replace the import directive. Usages are after the import, so the import
                //    offset is unaffected by the usage replacements above.
                val newImport = "import ${result.importedFqn} as $alias"
                val importLengthDelta = newImport.length - (importRange.endOffset - importRange.startOffset)
                newText = newText.substring(0, importRange.startOffset) +
                        newImport +
                        newText.substring(importRange.endOffset)

                // Compute caret target: stay at the trigger location, showing the alias there.
                val caretOffset = refactoring.caretOffset
                val caretTarget = if (caretOffset >= importRange.startOffset && caretOffset < importRange.endOffset) {
                    // Triggered from import line → alias identifier in the new import.
                    importRange.startOffset + "import ${result.importedFqn} as ".length
                } else {
                    // Triggered from a body usage → alias at that usage position.
                    val targetUsage = result.usageRanges.firstOrNull { r ->
                        r.startOffset <= caretOffset && caretOffset < r.endOffset
                    } ?: result.usageRanges.minByOrNull { r ->
                        minOf(Math.abs(r.startOffset - caretOffset), Math.abs(r.endOffset - caretOffset))
                    }
                    if (targetUsage != null) {
                        // Shift from usages at lower offsets than the target (back-to-front means
                        // these are processed last, but they were at original offsets in newText).
                        val lowerShift = result.usageRanges
                            .filter { it.endOffset <= targetUsage.startOffset }
                            .sumOf { alias.length - (it.endOffset - it.startOffset) }
                        // Import replacement shifts all body positions.
                        targetUsage.startOffset + lowerShift + importLengthDelta
                    } else {
                        // Fallback: alias in import line.
                        importRange.startOffset + "import ${result.importedFqn} as ".length
                    }
                }

                val atomicDoc = doc as? org.netbeans.editor.AtomicLockDocument
                val body: () -> Unit = {
                    if (doc.length > 0) doc.remove(0, doc.length)
                    doc.insertString(0, newText, null)
                }
                if (atomicDoc != null) {
                    atomicDoc.atomicLock()
                    try { body() } finally { atomicDoc.atomicUnlock() }
                } else {
                    NbDocument.runAtomicAsUser(doc) { body() }
                }

                // Move caret to the alias at the trigger location.
                SwingUtilities.invokeLater {
                    runCatching { moveCaretToOffset(doc, minOf(caretTarget, doc.length)) }
                }

                KotlinAnalysisAPISession.invalidate(nbProject)
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException("KotlinIntroduceImportAliasApplyElement.performChange failed", e)
            }
        } finally {
            activeBefore?.let { tc ->
                SwingUtilities.invokeLater { runCatching { tc.requestActive() } }
            }
        }
    }

    override fun undoChange() {
        runCatching {
            val fo = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: return@runCatching
            val doc = openDocument(fo) ?: return@runCatching
            val original = snapshot ?: return@runCatching
            NbDocument.runAtomicAsUser(doc) {
                if (doc.length > 0) doc.remove(0, doc.length)
                doc.insertString(0, original, null)
            }
            KotlinAnalysisAPISession.invalidate(nbProject)
        }
    }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }
}
