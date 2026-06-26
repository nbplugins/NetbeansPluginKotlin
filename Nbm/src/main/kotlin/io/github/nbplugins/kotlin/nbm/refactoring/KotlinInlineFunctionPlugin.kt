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
import io.github.nbplugins.kotlin.refactoring.KaInlineFunctionComputer
import io.github.nbplugins.kotlin.refactoring.KaInlineFunctionComputer.Companion.simplifyStringTemplateCaptures
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
import org.openide.text.NbDocument
import org.openide.text.PositionBounds
import org.openide.util.Lookup
import org.openide.util.lookup.Lookups
import org.openide.windows.TopComponent
import javax.swing.SwingUtilities
import javax.swing.text.BadLocationException
import javax.swing.text.Position.Bias
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that handles the Kotlin **Inline Function** refactoring.
 *
 * Bridges the NetBeans refactoring framework to the IDEA `codeInliner/` engine via
 * [KaInlineFunctionComputer]:
 *  1. `prepare()` runs IDEA's validation and builds the `CallableUsageReplacementStrategy`.
 *  2. If the function cannot be inlined, the user sees a fatal [Problem] with IDEA's message.
 *  3. Otherwise the preview pane shows one [KotlinFindUsagesResultElement] per reference and
 *     a single [KotlinInlineFunctionApplyElement] performs the work on confirm.
 *
 * The plugin **does not** instantiate IDEA's `BaseRefactoringProcessor`. Flow control stays in
 * NetBeans to avoid dragging in IDEA's modal-UI / threading runtime.
 *
 * @param refactoring the [KotlinInlineFunctionRefactoring] carrier
 */
class KotlinInlineFunctionPlugin(
    private val refactoring: KotlinInlineFunctionRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Resolves the function at the cursor, validates it, builds the IDEA replacement strategy,
     * finds usages, and populates [bag] with:
     *  - one [KotlinFindUsagesResultElement] per reference (read-only preview),
     *  - one [KotlinInlineFunctionApplyElement] that performs the actual transformation.
     *
     * @return a fatal [Problem] if the cursor is not on an inlineable function,
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

        val computer = KaInlineFunctionComputer(ktFile, offset, session.session.project, projectKtFiles)
        when (val outcome = computer.compute()) {
            is KaInlineFunctionComputer.Outcome.NotApplicable -> return null
            is KaInlineFunctionComputer.Outcome.Error ->
                return Problem(true, outcome.error.message)
            is KaInlineFunctionComputer.Outcome.Ready -> {
                val result = outcome.result

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

                val declFo = FileUtil.toFileObject(
                    FileUtil.normalizeFile(java.io.File(result.function.containingKtFile.virtualFile?.path ?: ""))
                ) ?: fo
                bag.add(
                    refactoring,
                    KotlinInlineFunctionApplyElement(declFo, nbProject, fo.path, offset),
                )
                return null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that performs the inline-function transformation.
 *
 * Strategy:
 *  1. Re-runs [KaInlineFunctionComputer] to get a fresh strategy against the current K2 session PSI.
 *  2. Drives IDEA's `CallableUsageReplacementStrategy` to inline each call site.
 *  3. Deletes the function declaration.
 *  4. Writes updated PSI text back to NetBeans documents and reformats each insertion range.
 *
 * Undo is an all-or-nothing snapshot restore (same approach as [KotlinInlineApplyElement] for variables).
 *
 * @param declarationFile  the file containing the function declaration
 * @param nbProject        the NetBeans project (needed to invalidate the K2 session post-apply)
 * @param cursorFilePath   virtual-file path of the file under the caret
 * @param cursorOffset     caret offset within [cursorFilePath]
 */
class KotlinInlineFunctionApplyElement(
    private val declarationFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val cursorFilePath: String,
    private val cursorOffset: Int,
) : SimpleRefactoringElementImplementation() {

    private val snapshots: MutableMap<FileObject, String> = mutableMapOf()

    override fun getText(): String = "Inline function"
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
                val session = KotlinAnalysisAPISession.getSession(nbProject)
                val cursorKtFile = session.getKtFileForPath(cursorFilePath) ?: return@runCatching
                val projectKtFiles = session.session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()

                val computer = KaInlineFunctionComputer(
                    cursorKtFile, cursorOffset, session.session.project, projectKtFiles,
                )
                val ready = computer.compute() as? KaInlineFunctionComputer.Outcome.Ready ?: return@runCatching
                val result = ready.result

                val affectedFiles = LinkedHashSet<KtFile>().apply {
                    addAll(result.usages.keys)
                    add(result.function.containingKtFile)
                }
                for (ktFile in affectedFiles) {
                    val path = ktFile.virtualFile?.path ?: continue
                    val fo = pathToFileObject(path) ?: continue
                    val text = readDocumentText(fo) ?: continue
                    snapshots[fo] = text
                }

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
                        val target: PsiElement = inserted.parent?.parent?.parent ?: inserted
                        insertedByFile.getOrPut(ktFile) { mutableListOf() }.add(target)
                    }
                }

                result.function.delete()

                // Write content + format insertions per file inside ONE atomic edit so
                // a single Ctrl+Z restores the pre-refactor state completely.
                for (ktFile in affectedFiles) {
                    val path = ktFile.virtualFile?.path ?: continue
                    val fo = pathToFileObject(path) ?: continue
                    val formattingRanges = insertedByFile[ktFile]
                        ?.mapNotNull { runCatching { it.textRange }.getOrNull() }
                        ?.sortedByDescending { it.startOffset }
                        ?: emptyList()
                    replaceDocumentAtomically(fo, simplifyStringTemplateCaptures(ktFile.text), formattingRanges)
                }

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
                replaceDocumentAtomically(fo, original)
            }
            KotlinAnalysisAPISession.invalidate(nbProject)
        }
    }

    private fun pathToFileObject(path: String): FileObject? =
        FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path)))

    private fun readDocumentText(fo: FileObject): String? = try {
        val doc = openDocument(fo) ?: return null
        doc.getText(0, doc.length)
    } catch (_: BadLocationException) { null } catch (_: Exception) { null }

    private fun openDocument(fo: FileObject): StyledDocument? = try {
        val dob = DataObject.find(fo)
        val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return null
        ec.openDocument()
    } catch (_: Exception) { null }

    /**
     * Replaces the entire document content with [newText] and reformats [formattingRanges],
     * all inside a single [org.netbeans.editor.AtomicLockDocument] block so one Ctrl+Z
     * restores the pre-refactor state atomically.
     *
     * Falls back to [NbDocument.runAtomicAsUser] when the document does not implement
     * [org.netbeans.editor.AtomicLockDocument] (uncommon in production, but safe).
     */
    private fun replaceDocumentAtomically(
        fo: FileObject,
        newText: String,
        formattingRanges: List<com.intellij.openapi.util.TextRange> = emptyList(),
    ) {
        try {
            val dob = DataObject.find(fo)
            val ec = dob.lookup.lookup(EditorCookie::class.java) ?: return
            val doc = ec.openDocument() ?: return
            val atomicDoc = doc as? org.netbeans.editor.AtomicLockDocument
            val body: () -> Unit = {
                if (doc.length > 0) doc.remove(0, doc.length)
                doc.insertString(0, newText, null)
                for (range in formattingRanges) {
                    val end = minOf(range.endOffset, doc.length)
                    val start = minOf(range.startOffset, end)
                    if (start < end) runCatching {
                        format(doc = doc, offset = start, startOffset = start, endOffset = end, proj = nbProject)
                    }
                }
            }
            if (atomicDoc != null) {
                atomicDoc.atomicLock()
                try { body() } finally { atomicDoc.atomicUnlock() }
            } else {
                NbDocument.runAtomicAsUser(doc) { body() }
            }
        } catch (_: BadLocationException) {
        } catch (_: Exception) {}
    }
}
