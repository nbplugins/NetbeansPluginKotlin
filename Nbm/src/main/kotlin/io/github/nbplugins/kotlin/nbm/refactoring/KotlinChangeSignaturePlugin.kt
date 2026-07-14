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

import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureComputer
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureRequest
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
 * [RefactoringPlugin] that implements the Kotlin **Change Signature** refactoring (E9.8, Ctrl+F6).
 *
 * `prepare()` validates the caret is on a function/constructor/class-with-primary-constructor,
 * previews the declaration range, and populates [bag] with a single
 * [KotlinChangeSignatureApplyElement]. No dry-run conflict check runs here (that would need a
 * second full usage search before the dialog even opens); [KaChangeSignatureComputer.apply] instead
 * checks conflicts once, right after usage search, and [KotlinChangeSignatureApplyElement] skips the
 * mutation (logging the conflict messages) if any are found — see [KaChangeSignatureComputer]'s
 * class doc.
 *
 * @param refactoring the carrier [KotlinChangeSignatureRefactoring]
 */
class KotlinChangeSignaturePlugin(
    private val refactoring: KotlinChangeSignatureRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaChangeSignatureComputer(ktFile, refactoring.caretOffset)
        return when (val outcome = computer.compute()) {
            is KaChangeSignatureComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinChangeSignaturePlugin.prepare: NotApplicable at offset " +
                            "${refactoring.caretOffset} in ${fo.path}"
                )
                null
            }
            is KaChangeSignatureComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException("KotlinChangeSignaturePlugin.prepare: Error", outcome.error)
                Problem(true, outcome.error.message ?: "Change Signature failed")
            }
            is KaChangeSignatureComputer.Outcome.Ready -> {
                bag.add(refactoring, KotlinChangeSignatureApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that applies the signature change across every
 * affected file.
 *
 * Sequencing (see `docs/development-plan.md`'s E9.8 section 4): [KaChangeSignatureComputer.apply]
 * performs the entire in-memory PSI rewrite in one call, returning a path→text map; this element
 * then, per touched file, opens the live NetBeans `Document` (not necessarily the file the caret
 * was in — Change Signature routinely touches files never opened in an editor) and replaces its
 * whole content inside `NbDocument.runAtomicAsUser`, mirroring the same whole-text-replace strategy
 * [KotlinMoveDeclarationApplyElement] already uses for its two files, generalized to N. A single
 * trailing [KotlinAnalysisAPISession.invalidate] refreshes the session once all files are written.
 *
 * Undo is not a single transaction in the underlying engine (multi-file mutation), but each file's
 * pre-change text is cheap to keep around (already read as `oldText` while diffing below), so
 * [undoChange] restores every touched file verbatim from a snapshot map — same "snapshot the whole
 * document, restore it whole" strategy [KotlinInlineApplyElement] uses, generalized from N files
 * found via usage search to N files here too. `Redo Last Refactoring` after an undo re-runs
 * [performChange] from scratch (NetBeans' refactoring framework calls it again), so no separate redo
 * bookkeeping is needed.
 *
 * @param callerFile   the file the caret was actually in (used to re-resolve the declaration)
 * @param nbProject    the NetBeans project
 * @param refactoring  the carrier holding the user-edited [KaChangeSignatureRequest]
 */
class KotlinChangeSignatureApplyElement(
    private val callerFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinChangeSignatureRefactoring,
) : SimpleRefactoringElementImplementation() {

    override fun getText(): String = "Change signature"
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

    override fun performChange() {
        runCatching {
            val request = refactoring.request ?: return@runCatching
            val session = KotlinAnalysisAPISession.getSession(nbProject)
            val callerKtFile = session.getKtFileForPath(callerFile.path) ?: return@runCatching

            val computer = KaChangeSignatureComputer(callerKtFile, refactoring.caretOffset)
            when (val outcome = computer.apply(request)) {
                is KaChangeSignatureComputer.ApplyOutcome.Success -> {
                    // Per-file try/catch, not one guard around the whole loop: Change Signature can
                    // touch dozens of files (every call site/override/reference project-wide), unlike
                    // Extract Function (1 file) or Move Declaration (2). If file k of N throws, files
                    // 1..k-1 are already written to their editors — letting the exception escape the
                    // loop would abort files k+1..N too without ever explaining which file failed or
                    // that the earlier ones already succeeded. No rollback is attempted (documented
                    // best-effort limitation, consistent with this project's stance elsewhere); each
                    // outcome is just logged per file so a partial failure is diagnosable.
                    var succeeded = 0
                    val failed = mutableListOf<String>()
                    for ((path, newText) in outcome.fileTexts) {
                        runCatching {
                            val fo = FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path))) ?: return@runCatching
                            val doc = openDocument(fo) ?: return@runCatching
                            val oldText = doc.getText(0, doc.length)
                            snapshots[fo] = oldText
                            // Replace and reformat only the regions that actually changed — a *vector*
                            // of small, disjoint hunks (TextRangeDiff.computeHunks, line-granular LCS
                            // refined to the smallest changed character span) rather than one region
                            // spanning from the first to the last difference: if the file has two call
                            // sites Change Signature updates with unrelated (even oddly-formatted) code
                            // between them, that untouched code must stay exactly as it was, not get
                            // swept into a "changed" span and reformatted along with the real edits.
                            // Per hunk this also (a) keeps the editor's native Undo to small edits near
                            // each edit site, matching MinimalDocumentEdits' rationale, and (b) the
                            // ported engine's psiFactory-generated text (parameter lists, call
                            // arguments) is not itself re-run through the code formatter — e.g. missing
                            // space after a comma in "greet(\"world\",second)" — so a reformat pass is
                            // still needed, just scoped to each hunk instead of the whole file.
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
                                "KotlinChangeSignatureApplyElement: failed to write $path (${succeeded + failed.size}/${outcome.fileTexts.size} files processed so far)", e
                            )
                        }
                    }
                    if (failed.isNotEmpty()) {
                        KotlinLogger.INSTANCE.logWarning(
                            "KotlinChangeSignatureApplyElement: $succeeded/${outcome.fileTexts.size} files written successfully; " +
                                "failed: $failed — the refactoring is incomplete, use your VCS or Undo Last Refactoring to review/revert"
                        )
                    }
                }
                is KaChangeSignatureComputer.ApplyOutcome.Conflicts -> {
                    KotlinLogger.INSTANCE.logWarning(
                        "KotlinChangeSignatureApplyElement: change skipped, conflicts found: ${outcome.messages}"
                    )
                }
                is KaChangeSignatureComputer.ApplyOutcome.Error -> {
                    KotlinLogger.INSTANCE.logException("KotlinChangeSignatureApplyElement: apply failed", outcome.error)
                }
            }

            KotlinAnalysisAPISession.invalidate(nbProject)
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinChangeSignatureApplyElement.performChange failed", e)
        }
    }

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
            KotlinLogger.INSTANCE.logException("KotlinChangeSignatureApplyElement.undoChange failed", e)
        }
    }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }
}
