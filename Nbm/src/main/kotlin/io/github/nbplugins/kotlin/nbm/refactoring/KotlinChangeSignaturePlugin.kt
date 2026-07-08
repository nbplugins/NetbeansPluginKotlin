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
 * [KotlinChangeSignatureApplyElement]. Conflict-checking is deferred past M1 (see
 * [KaChangeSignatureComputer]'s class doc), so no dry-run conflict check runs here yet.
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
 * Undo is not supported by the underlying engine as a single transaction (multi-file mutation);
 * [undoChange] logs a warning listing every touched file, since this refactoring's blast radius is
 * larger than Move Declaration's.
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

    private var touchedFiles: List<FileObject> = emptyList()

    override fun performChange() {
        runCatching {
            val request = refactoring.request ?: return@runCatching
            val session = KotlinAnalysisAPISession.getSession(nbProject)
            val callerKtFile = session.getKtFileForPath(callerFile.path) ?: return@runCatching

            val computer = KaChangeSignatureComputer(callerKtFile, refactoring.caretOffset)
            when (val outcome = computer.apply(request)) {
                is KaChangeSignatureComputer.ApplyOutcome.Success -> {
                    val written = mutableListOf<FileObject>()
                    for ((path, newText) in outcome.fileTexts) {
                        val fo = FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path))) ?: continue
                        val doc = openDocument(fo) ?: continue
                        NbDocument.runAtomicAsUser(doc) {
                            if (doc.length > 0) doc.remove(0, doc.length)
                            doc.insertString(0, newText, null)
                        }
                        written.add(fo)
                    }
                    touchedFiles = written
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
        KotlinLogger.INSTANCE.logWarning(
            "KotlinChangeSignatureApplyElement: undo is not supported (Change Signature mutates " +
                    "multiple files); use your VCS or manual edits to revert. Touched files: " +
                    touchedFiles.joinToString { it.path }
        )
    }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }
}
