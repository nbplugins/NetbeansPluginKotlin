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

import com.intellij.psi.PsiElement
import io.github.nbplugins.kotlin.nbm.navigation.KotlinFindUsagesResultElement
import io.github.nbplugins.kotlin.nbm.reformatting.format
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaInlineVariableComputer
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
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
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import org.openide.windows.TopComponent
import javax.swing.SwingUtilities
import javax.swing.text.BadLocationException
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that handles the Kotlin **Inline Variable** refactoring.
 *
 * Bridges the NetBeans refactoring framework to the IDEA `codeInliner/` engine ported into
 * [io.github.nbplugins.kotlin.refactoring.KaInlineVariableComputer]:
 *  1. `prepare()` runs the IDEA validation and reference-search via the computer.
 *  2. If the property cannot be inlined the user sees a fatal [Problem] with IDEA's message.
 *  3. Otherwise the preview pane shows one [KotlinFindUsagesResultElement] per reference and
 *     a single [KotlinInlineApplyElement] performs the work on confirm.
 *
 * The actual code transformation is delegated to the IDEA strategy
 * (`PropertyUsageReplacementStrategy` + `CodeInliner`), which handles parenthesisation,
 * qualified-call rewriting, imports and other edge cases the previous hand-written stub did not.
 *
 * The plugin **does not** instantiate IDEA's `BaseRefactoringProcessor`. Flow control stays in
 * NetBeans so we avoid dragging in IDEA's modal-UI / threading runtime. The price is that
 * fine-grained per-usage Undo is not currently wired — undo is a single all-or-nothing step
 * (see [KotlinInlineApplyElement]).
 *
 * @param refactoring the [KotlinInlineVariableRefactoring] carrier whose Lookup contains
 *                    the target [StyledDocument] and the caret offset as [Int]
 */
class KotlinInlineVariablePlugin(
    private val refactoring: KotlinInlineVariableRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Resolves the symbol at the cursor, validates it via IDEA's `extractInitialization`,
     * builds the IDEA replacement strategy, finds usages, and populates [bag] with:
     *  - one [KotlinFindUsagesResultElement] per reference (read-only preview),
     *  - one [KotlinInlineApplyElement] that performs the actual transformation.
     *
     * @return a fatal [Problem] if the cursor is not on an inlineable `val`/`var`,
     *         `null` otherwise (the framework then shows the preview dialog)
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.refactoringSource.lookup(StyledDocument::class.java) ?: return null
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val offset = refactoring.refactoringSource.lookup(Int::class.javaObjectType) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null
        val projectKtFiles = session.session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()

        val computer = KaInlineVariableComputer(ktFile, offset, session.session.project, projectKtFiles)
        when (val outcome = computer.compute()) {
            is KaInlineVariableComputer.Outcome.NotApplicable -> return null
            is KaInlineVariableComputer.Outcome.Error ->
                return Problem(true, outcome.error.message)
            is KaInlineVariableComputer.Outcome.Ready -> {
                val result = outcome.result

                // Preview items: one read-only entry per usage range, grouped by file.
                for ((usageKtFile, refs) in result.usages) {
                    val usageFo = FileUtil.toFileObject(
                        FileUtil.normalizeFile(java.io.File(usageKtFile.virtualFile?.path ?: continue))
                    ) ?: continue
                    for (ref in refs) {
                        runCatching {
                            bag.add(
                                refactoring,
                                KotlinFindUsagesResultElement(
                                    OffsetRange(ref.textRange.startOffset, ref.textRange.endOffset),
                                    usageFo,
                                ),
                            )
                        }
                    }
                }

                // The element that actually mutates documents on confirm.
                val declFo = FileUtil.toFileObject(
                    FileUtil.normalizeFile(java.io.File(result.property.containingKtFile.virtualFile?.path ?: ""))
                ) ?: fo
                bag.add(
                    refactoring,
                    KotlinInlineApplyElement(declFo, nbProject, fo.path, offset),
                )
                return null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that performs the inline transformation.
 *
 * Strategy:
 *  1. On apply, the element re-runs the [KaInlineVariableComputer] (so the strategy is fresh
 *     against the current K2 session PSI), then drives the IDEA `PropertyUsageReplacementStrategy`
 *     to inline each usage in place, and finally deletes the property declaration.
 *  2. **Per-file snapshots** of the document text are captured before mutating PSI so [undoChange]
 *     can roll back every affected file in one shot.
 *  3. After mutating the session PSI, each affected `KtFile`'s new text is written back to its
 *     corresponding NetBeans document. The K2 session is invalidated so the next analysis cycle
 *     rebuilds the PSI from the now-current document content.
 *
 * Per-usage Undo is not wired in this iteration (a future enhancement may split the apply step
 * into one element per usage, similar to Rename). For E9.3 MVP a coarse all-or-nothing undo is
 * acceptable and matches NB Safe Delete semantics.
 *
 * @param declarationFile  the file containing the property declaration (parent file for
 *                         RefactoringElementsBag grouping in the preview)
 * @param nbProject        the NetBeans project (needed to invalidate the K2 session post-apply)
 * @param cursorFilePath   virtual-file path of the file under the caret when the action was invoked
 * @param cursorOffset     caret offset within the file at [cursorFilePath]
 */
class KotlinInlineApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val cursorFilePath: String,
    private val cursorOffset: Int,
) : SimpleRefactoringElementImplementation() {

    /** Saved per-file document text captured in [performChange] for [undoChange] to restore. */
    private val snapshots: MutableMap<FileObject, String> = mutableMapOf()

    override fun getText(): String = "Inline variable"
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
        // Capture the TopComponent active before document writes — `writeDocumentText` activates
        // the editor of the modified file, which steals focus from the file the user was working
        // on (especially noticeable when usages live in a different file). Restored in `finally`.
        val activeBefore: TopComponent? = runCatching { TopComponent.getRegistry().activated }.getOrNull()
        try {
            runCatching {
                val session = KotlinAnalysisAPISession.getSession(nbProject)
                val cursorKtFile = session.getKtFileForPath(cursorFilePath) ?: return@runCatching
                val projectKtFiles = session.session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()

                val computer = KaInlineVariableComputer(
                    cursorKtFile, cursorOffset, session.session.project, projectKtFiles,
                )
                val ready = computer.compute() as? KaInlineVariableComputer.Outcome.Ready ?: return@runCatching
                val result = ready.result

                // Snapshot every file we are about to modify. Required by undoChange().
                val affectedFiles = LinkedHashSet<KtFile>().apply {
                    addAll(result.usages.keys)
                    add(result.property.containingKtFile)
                }
                for (ktFile in affectedFiles) {
                    val path = ktFile.virtualFile?.path ?: continue
                    val fo = pathToFileObject(path) ?: continue
                    val text = readDocumentText(fo) ?: continue
                    snapshots[fo] = text
                }

                // Replicates IDEA's `processUsages` per-file loop (UsageReplacementStrategy.kt:60-108):
                // sort usages so nested ones go first; for each, fetch the replacer and run it. We
                // do NOT use IDEA's `replaceUsages` bulk extension because we need access to the
                // inserted PSI element per usage to reformat its range afterwards (IDEA's
                // `reformatted(true)` call is a no-op in our standalone container).
                val insertedByFile = LinkedHashMap<KtFile, MutableList<PsiElement>>()
                for ((ktFile, usagesInFile) in result.usages) {
                    val sortedUsages = usagesInFile.sortedWith { a, b ->
                        if (a.parent.textRange.intersects(b.parent.textRange)) {
                            compareValuesBy(b, a) { it.startOffset }
                        } else {
                            compareValuesBy(a, b) { it.startOffset }
                        }
                    }
                    for (usage in sortedUsages) {
                        if (!usage.isValid) continue
                        val replacer = runCatching { result.strategy.createReplacer(usage) }.getOrNull() ?: continue
                        val inserted = runCatching { replacer.invoke() }.getOrNull() ?: continue
                        // Mirror IDEA's reformat target (`inserted.parent.parent.parent` in
                        // UsageReplacementStrategy.kt:102) — gives the local context around the
                        // insertion so the formatter has enough scope to reflow the result.
                        val target: PsiElement = inserted.parent?.parent?.parent ?: inserted
                        insertedByFile.getOrPut(ktFile) { mutableListOf() }.add(target)
                    }
                }

                result.property.delete()

                // Push the post-mutation text of every affected KtFile back to its NB document so
                // the editor and on-disk state reflect the refactor.
                for (ktFile in affectedFiles) {
                    val path = ktFile.virtualFile?.path ?: continue
                    val fo = pathToFileObject(path) ?: continue
                    writeDocumentText(fo, ktFile.text)
                }

                // Reformat each inserted range using our text-based KotlinFormatter. Ranges are
                // sorted descending by start offset so earlier ranges' offsets remain valid as
                // later ones are reformatted (a single format() call rewrites the full document,
                // but only the supplied range is reflowed).
                for ((ktFile, elements) in insertedByFile) {
                    val path = ktFile.virtualFile?.path ?: continue
                    val fo = pathToFileObject(path) ?: continue
                    val doc = openDocument(fo) ?: continue
                    val ranges = elements.mapNotNull { runCatching { it.textRange }.getOrNull() }
                        .sortedByDescending { it.startOffset }
                    for (range in ranges) {
                        val end = minOf(range.endOffset, doc.length)
                        val start = minOf(range.startOffset, end)
                        if (start >= end) continue
                        runCatching {
                            format(
                                doc = doc,
                                offset = start,
                                startOffset = start,
                                endOffset = end,
                                proj = nbProject,
                            )
                        }
                    }
                }

                // Drop the K2 cache so subsequent operations parse the new content from disk.
                KotlinAnalysisAPISession.invalidate(nbProject)
            }
        } finally {
            activeBefore?.let { tc ->
                SwingUtilities.invokeLater { runCatching { tc.requestActive() } }
            }
        }
    }

    override fun undoChange() {
        runCatching {
            for ((fo, original) in snapshots) {
                writeDocumentText(fo, original)
            }
            KotlinAnalysisAPISession.invalidate(nbProject)
        }
    }

    /**
     * Resolves a K2 virtual-file path to its NetBeans [FileObject], returning `null` when the
     * path refers to a transient or in-memory file (e.g. test fixtures created via `LightVirtualFile`).
     */
    private fun pathToFileObject(path: String): FileObject? =
        FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path)))

    /** Reads the current text of the open document for [fo], or `null` when the document cannot be opened. */
    private fun readDocumentText(fo: FileObject): String? = try {
        val doc = openDocument(fo) ?: return null
        doc.getText(0, doc.length)
    } catch (_: BadLocationException) { null } catch (_: Exception) { null }

    /** Opens (or returns the already-open) [StyledDocument] for [fo], or `null` on error. */
    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }

    /** Replaces the entire text of the open document for [fo] with [newText]. No-op on errors. */
    private fun writeDocumentText(fo: FileObject, newText: String) {
        try {
            val dob = DataObject.find(fo)
            val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return
            val doc = ec.openDocument() ?: return
            if (doc.length > 0) doc.remove(0, doc.length)
            doc.insertString(0, newText, null)
        } catch (_: BadLocationException) {
        } catch (_: Exception) {}
    }
}

