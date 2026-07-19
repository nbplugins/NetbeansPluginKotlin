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

import io.github.nbplugins.kotlin.nbm.navigation.KotlinFindUsagesResultElement
import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaIntroduceFunctionalParameterComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.refactoring.api.Problem
import org.netbeans.modules.refactoring.spi.ProgressProviderAdapter
import org.netbeans.modules.refactoring.spi.RefactoringElementsBag
import org.netbeans.modules.refactoring.spi.RefactoringPlugin
import org.netbeans.modules.refactoring.spi.SimpleRefactoringElementImplementation
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject
import org.openide.text.CloneableEditorSupport
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that implements the Kotlin **Introduce Functional Parameter** refactoring
 * (E9.14, Ctrl+Alt+Shift+P).
 *
 * `prepare()` validates the selected expression via [KaIntroduceFunctionalParameterComputer.compute]
 * and populates [bag] with one [KotlinFindUsagesResultElement] for the selection (preview pane)
 * plus a single [KotlinIntroduceFunctionalParameterApplyElement]. Same silent-skip/conflict-check
 * convention as [KotlinIntroduceParameterPlugin].
 *
 * @param refactoring the carrier [KotlinIntroduceFunctionalParameterRefactoring]
 */
class KotlinIntroduceFunctionalParameterPlugin(
    private val refactoring: KotlinIntroduceFunctionalParameterRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the selected expression and populates [bag] with a preview element plus the
     * [KotlinIntroduceFunctionalParameterApplyElement] that performs the actual transformation.
     *
     * @return a fatal [Problem] if analysis failed with an exception, `null` otherwise (including
     *   when the selection is simply not applicable — same silent-skip convention as every other
     *   E9.x `RefactoringPlugin`)
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaIntroduceFunctionalParameterComputer(
            ktFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
        )
        return when (val outcome = computer.compute()) {
            is KaIntroduceFunctionalParameterComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinIntroduceFunctionalParameterPlugin.prepare: NotApplicable at offsets " +
                            "${refactoring.startOffset}..${refactoring.endOffset} in ${fo.path}"
                )
                null
            }
            is KaIntroduceFunctionalParameterComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException("KotlinIntroduceFunctionalParameterPlugin.prepare: Error", outcome.error)
                Problem(true, outcome.error.message ?: "Introduce Functional Parameter failed")
            }
            is KaIntroduceFunctionalParameterComputer.Outcome.Ready -> {
                val result = outcome.result
                runCatching {
                    bag.add(
                        refactoring,
                        KotlinFindUsagesResultElement(
                            org.netbeans.modules.csl.api.OffsetRange(
                                result.selectionRange.startOffset, result.selectionRange.endOffset,
                            ),
                            fo,
                        ),
                    )
                }
                bag.add(refactoring, KotlinIntroduceFunctionalParameterApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that adds the new functional parameter, updates
 * every call site with a lambda literal, and replaces the primary (and, when requested, duplicate)
 * occurrences in the body with calls to the parameter.
 *
 * [KaIntroduceFunctionalParameterComputer.apply] performs the entire in-memory PSI rewrite in one
 * call, returning a path→text map; this element then, per touched file, opens the live NetBeans
 * `Document` and applies only the changed hunks via [TextRangeDiff.computeHunks] — the same
 * multi-file, hunk-granular write strategy [KotlinIntroduceParameterApplyElement] uses.
 *
 * @param callerFile the file the caret/selection was actually in (used to re-resolve the expression)
 * @param nbProject  the NetBeans project
 * @param refactoring the carrier holding the user-edited [io.github.nbplugins.kotlin.refactoring.KaIntroduceFunctionalParameterRequest]
 */
class KotlinIntroduceFunctionalParameterApplyElement(
    private val callerFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinIntroduceFunctionalParameterRefactoring,
) : SimpleRefactoringElementImplementation() {

    override fun getText(): String = "Introduce functional parameter"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(callerFile)
    override fun getParentFile(): FileObject = callerFile

    override fun getPosition(): PositionBounds? = try {
        val dob = DataObject.find(callerFile)
        val ces = dob.lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        val start = ces.createPositionRef(0, Bias.Forward)
        val end = ces.createPositionRef(0, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    /** Per-file pre-change text captured in [performChange], restored verbatim by [undoChange]. */
    private val snapshots: MutableMap<FileObject, String> = mutableMapOf()

    /** Applies [refactoring]'s request via [KaIntroduceFunctionalParameterComputer.apply] and writes every touched file. */
    override fun performChange() {
        runCatching {
            val request = refactoring.request ?: return@runCatching
            val session = KotlinAnalysisAPISession.getSession(nbProject)
            val callerKtFile = session.getKtFileForPath(callerFile.path) ?: return@runCatching

            val computer = KaIntroduceFunctionalParameterComputer(
                callerKtFile, refactoring.startOffset, refactoring.endOffset, session.session.project,
            )
            when (val outcome = computer.apply(request)) {
                is KaIntroduceFunctionalParameterComputer.ApplyOutcome.Success -> {
                    var succeeded = 0
                    val failed = mutableListOf<String>()
                    for ((path, newText) in outcome.fileTexts) {
                        runCatching {
                            val fo = FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path))) ?: return@runCatching
                            val doc = openDocument(fo) ?: return@runCatching
                            val oldText = doc.getText(0, doc.length)
                            snapshots[fo] = oldText
                            val hunks = TextRangeDiff.computeHunks(oldText, newText).sortedByDescending { it.oldStart }
                            NbDocument.runAtomicAsUser(doc) {
                                for (hunk in hunks) {
                                    if (hunk.oldEnd > hunk.oldStart) doc.remove(hunk.oldStart, hunk.oldEnd - hunk.oldStart)
                                    val replacement = newText.substring(hunk.newStart, hunk.newEnd)
                                    if (replacement.isNotEmpty()) doc.insertString(hunk.oldStart, replacement, null)
                                    val formatEnd = hunk.oldStart + replacement.length
                                    if (formatEnd > hunk.oldStart) {
                                        runCatching { format(doc = doc, offset = hunk.oldStart, startOffset = hunk.oldStart, endOffset = formatEnd, proj = nbProject) }
                                    }
                                }
                            }
                            succeeded++
                        }.onFailure { e ->
                            failed += path
                            KotlinLogger.INSTANCE.logException(
                                "KotlinIntroduceFunctionalParameterApplyElement: failed to write $path (${succeeded + failed.size}/${outcome.fileTexts.size} files processed so far)", e
                            )
                        }
                    }
                    if (failed.isNotEmpty()) {
                        KotlinLogger.INSTANCE.logWarning(
                            "KotlinIntroduceFunctionalParameterApplyElement: $succeeded/${outcome.fileTexts.size} files written successfully; " +
                                "failed: $failed — the refactoring is incomplete, use your VCS or Undo Last Refactoring to review/revert"
                        )
                    }
                }
                is KaIntroduceFunctionalParameterComputer.ApplyOutcome.Conflicts -> {
                    KotlinLogger.INSTANCE.logWarning(
                        "KotlinIntroduceFunctionalParameterApplyElement: change skipped, conflicts found: ${outcome.messages}"
                    )
                }
                is KaIntroduceFunctionalParameterComputer.ApplyOutcome.Error -> {
                    KotlinLogger.INSTANCE.logException("KotlinIntroduceFunctionalParameterApplyElement: apply failed", outcome.error)
                }
            }

            KotlinAnalysisAPISession.invalidate(nbProject)
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinIntroduceFunctionalParameterApplyElement.performChange failed", e)
        }
    }

    /** Restores every touched file verbatim from the snapshot captured in [performChange]. */
    override fun undoChange() {
        runCatching {
            for ((fo, originalText) in snapshots) {
                val doc = openDocument(fo) ?: continue
                NbDocument.runAtomicAsUser(doc) {
                    if (doc.length > 0) doc.remove(0, doc.length)
                    doc.insertString(0, originalText, null)
                }
            }
            KotlinAnalysisAPISession.invalidate(nbProject)
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinIntroduceFunctionalParameterApplyElement.undoChange failed", e)
        }
    }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }
}
