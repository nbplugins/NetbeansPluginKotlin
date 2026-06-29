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
 * Strategy:
 *  1. Re-runs [KaCopyDeclarationComputer] to get a fresh result.
 *  2. Creates a new `.kt` file in the same directory as the source file.
 *  3. Writes the package declaration and the copied declaration text to the new file.
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

            // Build the content of the new file.
            // neededImports comes from K2 retargeting: all FQNs referenced inside the declaration
            // that are not in the default Kotlin/Java imports and not in the same package.
            val packageLine = if (result.packageName.isNotEmpty()) "package ${result.packageName}\n\n" else ""
            val importsBlock = if (result.neededImports.isNotEmpty())
                result.neededImports.joinToString("\n") + "\n\n"
            else ""
            val newFileContent = packageLine + importsBlock + result.declarationText + "\n"

            // Create (or overwrite) the target file.
            val existingFo = parentDir.getFileObject(targetSimple)
            val targetFo = existingFo ?: parentDir.createData(targetSimple)
            targetFo.getOutputStream().use { out ->
                out.write(newFileContent.toByteArray(Charsets.UTF_8))
            }
            createdFile = if (existingFo == null) targetFo else null

            KotlinAnalysisAPISession.invalidate(nbProject)
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException("KotlinCopyDeclarationApplyElement.performChange failed", e)
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
