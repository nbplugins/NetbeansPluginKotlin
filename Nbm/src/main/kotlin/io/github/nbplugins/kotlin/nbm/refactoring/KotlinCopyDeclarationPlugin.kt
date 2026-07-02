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
import io.github.nbplugins.kotlin.refactoring.KaCopyDeclarationComputer
import org.jetbrains.kotlin.log.KotlinLogger
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
import javax.swing.text.StyledDocument

/**
 * [RefactoringPlugin] that implements the Kotlin **Copy Declaration** refactoring.
 *
 * Bridges the NetBeans refactoring framework to [KaCopyDeclarationComputer]:
 *  1. `prepare()` validates the top-level declaration at the caret and populates [bag] with the
 *     source declaration element and a single [KotlinCopyDeclarationApplyElement].
 *  2. A fatal [Problem] is returned if the caret is not inside a top-level named declaration.
 *
 * @param refactoring the carrier [KotlinCopyDeclarationRefactoring]
 */
class KotlinCopyDeclarationPlugin(
    private val refactoring: KotlinCopyDeclarationRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {

    override fun preCheck(): Problem? = null
    override fun fastCheckParameters(): Problem? = null
    override fun checkParameters(): Problem? = null
    override fun cancelRequest() {}

    /**
     * Analyses the declaration at the caret and populates [bag] with:
     *  - one [KotlinFindUsagesResultElement] for the source declaration (preview pane),
     *  - one [KotlinCopyDeclarationApplyElement] that performs the copy.
     *
     * @return a fatal [Problem] if the caret is not on a top-level declaration, `null` otherwise
     */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val doc = refactoring.doc
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val nbProject = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return null

        val session = KotlinAnalysisAPISession.getSession(nbProject)
        val ktFile = session.getKtFileForPath(fo.path) ?: return null

        val computer = KaCopyDeclarationComputer(ktFile, refactoring.caretOffset)
        return when (val outcome = computer.compute()) {
            is KaCopyDeclarationComputer.Outcome.NotApplicable -> {
                KotlinLogger.INSTANCE.logWarning(
                    "KotlinCopyDeclarationPlugin.prepare: NotApplicable at offset " +
                            "${refactoring.caretOffset} in ${fo.path}"
                )
                null
            }
            is KaCopyDeclarationComputer.Outcome.Error -> {
                KotlinLogger.INSTANCE.logException(
                    "KotlinCopyDeclarationPlugin.prepare: Error", outcome.error
                )
                Problem(true, outcome.error.message ?: "Copy Declaration failed")
            }
            is KaCopyDeclarationComputer.Outcome.Ready -> {
                val result = outcome.result
                runCatching {
                    bag.add(
                        refactoring,
                        KotlinFindUsagesResultElement(
                            OffsetRange(result.declarationRange.startOffset, result.declarationRange.endOffset),
                            fo,
                        ),
                    )
                }
                bag.add(refactoring, KotlinCopyDeclarationApplyElement(fo, nbProject, refactoring))
                null
            }
        }
    }
}

/**
 * The single all-or-nothing refactoring element that performs the copy-declaration transformation.
 *
 * Mirrors IDEA's K2 `CopyKotlinDeclarationsHandler.doRefactor`, which has two paths:
 *  1. **Sole declaration in file** → the whole source file is copied verbatim (all imports
 *     preserved); the new file is written from [KaCopyDeclarationResult.sourceFileText].
 *  2. **One of several declarations** → the target file is seeded with the package directive, the
 *     K2 session is refreshed so the file joins the source module, and the real IDEA retargeting
 *     engine ([io.github.nbplugins.kotlin.refactoring.KaCopyDeclarationComputer.copyDeclarationInto]
 *     → `K2MoveRenameUsageInfo`) copies the declaration and rebinds its internal usages on PSI.
 *
 * Undo deletes the created file.
 *
 * @param sourceFile   the file containing the declaration
 * @param nbProject    the NetBeans project
 * @param refactoring  the carrier holding the target file name
 */
class KotlinCopyDeclarationApplyElement(
    private val sourceFile: FileObject,
    private val nbProject: org.netbeans.api.project.Project,
    private val refactoring: KotlinCopyDeclarationRefactoring,
) : SimpleRefactoringElementImplementation() {

    /** The file created during [performChange], used by [undoChange] to delete it. */
    private var createdFile: FileObject? = null

    override fun getText(): String = "Copy declaration"
    override fun getDisplayText(): String = getText()
    override fun getLookup(): Lookup = Lookups.fixed(sourceFile)
    override fun getParentFile(): FileObject = sourceFile

    override fun getPosition(): PositionBounds? = try {
        val dob = DataObject.find(sourceFile)
        val ces = dob.lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        val start = ces.createPositionRef(0, Bias.Forward)
        val end = ces.createPositionRef(0, Bias.Backward)
        PositionBounds(start, end)
    } catch (_: Exception) { null }

    override fun performChange() {
        runCatching {
            val fo = ProjectUtils.getFileObjectForDocument(refactoring.doc) ?: return@runCatching
            val nbProject2 = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return@runCatching
            val session = KotlinAnalysisAPISession.getSession(nbProject2)
            val ktFile = session.getKtFileForPath(fo.path) ?: return@runCatching

            val computer = KaCopyDeclarationComputer(ktFile, refactoring.caretOffset)
            val outcome = computer.compute()
            val ready = outcome as? KaCopyDeclarationComputer.Outcome.Ready ?: return@runCatching
            val result = ready.result

            val targetName = refactoring.targetFileName.ifBlank { result.suggestedFileName }
            val targetSimple = if (targetName.endsWith(".kt")) targetName else "$targetName.kt"

            val parentDir = fo.parent ?: return@runCatching
            val existingFo = parentDir.getFileObject(targetSimple)
            val targetFo = existingFo ?: parentDir.createData(targetSimple)
            createdFile = if (existingFo == null) targetFo else null

            if (result.isSoleDeclarationInFile) {
                // IDEA's doRefactoringOnFile path: the declaration is the only one in its file, so
                // the whole file is copied verbatim — every import is preserved.
                writeFile(targetFo, result.sourceFileText)
            } else {
                // IDEA's doRefactoringOnElement path: seed the target with the package directive,
                // refresh the session so the new file joins the source module, then run the real
                // K2 retargeting engine (markInternalUsages + retargetUsages) on PSI and persist
                // the resulting file text.
                val packageLine = if (result.packageName.isNotEmpty()) "package ${result.packageName}\n\n" else ""
                writeFile(targetFo, packageLine)

                KotlinAnalysisAPISession.invalidate(nbProject2)
                val session2 = KotlinAnalysisAPISession.getSession(nbProject2)
                val sourceKtFile = session2.getKtFileForPath(fo.path)
                val targetKtFile = session2.getKtFileForPath(targetFo.path)
                if (sourceKtFile != null && targetKtFile != null) {
                    val computer2 = KaCopyDeclarationComputer(sourceKtFile, refactoring.caretOffset)
                    val copied = computer2.copyDeclarationInto(targetKtFile)
                    if (copied != null) {
                        writeFile(targetFo, targetKtFile.text)
                    }
                }
            }

            KotlinAnalysisAPISession.invalidate(nbProject)
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinCopyDeclarationApplyElement.performChange failed", e)
        }
    }

    /** Overwrites [fo] with [content] (UTF-8). */
    private fun writeFile(fo: FileObject, content: String) {
        fo.getOutputStream().use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    override fun undoChange() {
        runCatching {
            createdFile?.delete()
            createdFile = null
            KotlinAnalysisAPISession.invalidate(nbProject)
        }
    }
}
