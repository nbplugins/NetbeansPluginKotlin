/*******************************************************************************
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
import io.github.nbplugins.kotlin.refactoring.KaChangePackageComputer
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.name.FqName
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
import javax.swing.text.Position.Bias
import java.util.LinkedHashMap
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Bridges NetBeans Change Kotlin Package UI to the standalone K2 semantic adapter.
 *
 * @param refactoring selected sources and destination parameters from the NetBeans UI
 */
class KotlinChangePackagePlugin(
    private val refactoring: KotlinChangePackageRefactoring,
) : ProgressProviderAdapter(), RefactoringPlugin {
    /** @return no preflight problem; sources are checked during prepare. */
    override fun preCheck(): Problem? = null
    /** @return no fast problem; UI validates the package text. */
    override fun fastCheckParameters(): Problem? = null
    /** @return no extra parameter problem. */
    override fun checkParameters(): Problem? = null
    /** Cancels no external asynchronous work. */
    override fun cancelRequest() = Unit

    /** Adds selected source previews and one atomic package-change element. */
    override fun prepare(bag: RefactoringElementsBag): Problem? {
        val selection = refactoring.selection
        val project = ProjectUtils.getKotlinProjectForFileObject(selection.representativeFile)
            ?: ProjectUtils.getValidProject()
            ?: return Problem(true, "Change Kotlin Package could not find the source project.")
        val session = KotlinAnalysisAPISession.getSession(project)
        selection.sourceFiles.forEach { source ->
            val file = session.getKtFileForPath(source.path)
                ?: return Problem(true, "Change Kotlin Package could not resolve ${source.nameExt}.")
            bag.add(refactoring, KotlinFindUsagesResultElement(OffsetRange(0, file.textLength), source))
        }
        bag.add(refactoring, KotlinChangePackageApplyElement(selection, project, refactoring))
        return null
    }
}

/**
 * Applies one atomic package change and retains its transaction for Undo Last Refactoring.
 *
 * @param selection Kotlin sources whose paths must remain unchanged
 * @param project owning NetBeans project
 * @param refactoring requested package settings
 */
class KotlinChangePackageApplyElement(
    private val selection: KotlinChangePackageSourceSelection,
    private val project: org.netbeans.api.project.Project,
    private val refactoring: KotlinChangePackageRefactoring,
) : SimpleRefactoringElementImplementation() {
    private var transaction: KotlinRefactoringTransaction? = null
    private var undoCaretOffsets: Map<FileObject, Int> = emptyMap()

    /** @return preview label for this all-or-nothing operation. */
    override fun getText(): String = "Change Kotlin package"
    /** @return preview label for this all-or-nothing operation. */
    override fun getDisplayText(): String = text
    /** @return representative source lookup. */
    override fun getLookup(): Lookup = Lookups.fixed(selection.representativeFile)
    /** @return representative source for preview placement. */
    override fun getParentFile(): FileObject = selection.representativeFile
    /** @return source bounds when the editor support is available. */
    override fun getPosition(): PositionBounds? = runCatching {
        val support = org.openide.loaders.DataObject.find(selection.representativeFile)
            .lookup.lookup(CloneableEditorSupport::class.java) ?: return null
        PositionBounds(support.createPositionRef(0, Bias.Forward), support.createPositionRef(0, Bias.Backward))
    }.getOrNull()

    /** Runs K2 semantics and persists every changed document through one transaction. */
    override fun performChange() {
        var pending: KotlinRefactoringTransaction? = null
        val operationId = UUID.randomUUID().toString()
        runCatching {
            val basePackage = refactoring.targetPackage.trim()
            check(KotlinPackageTarget(project, selection.representativeFile).isValidPackage(basePackage)) {
                "Target package is invalid: $basePackage"
            }
            KotlinAnalysisAPISession.invalidate(project)
            val session = KotlinAnalysisAPISession.getSession(project)
            session.synchronizeOpenDocuments()
            val sourceByPath = selection.sourceFiles.associateBy(FileObject::getPath)
            val targets = selection.sourceFiles.associate { source ->
                val file = session.getKtFileForPath(source.path)
                    ?: error("Change Kotlin Package could not resolve writable source PSI: ${source.nameExt}.")
                val segments = basePackage.split('.').filter(String::isNotEmpty) + selection.targetRelativePackageSegments(source)
                file to FqName(segments.joinToString("."))
            }
            val current = KotlinRefactoringTransaction()
            pending = current
            when (val outcome = KaChangePackageComputer(targets.keys.first()).apply(targets, refactoring.updateReferences)) {
                is KaChangePackageComputer.ApplyOutcome.Conflicts -> {
                    throw IllegalStateException(outcome.messages.joinToString("\n"))
                }
                is KaChangePackageComputer.ApplyOutcome.Error -> {
                    KotlinLogger.INSTANCE.logException(
                        "KotlinChangePackageApplyElement.performChange: semantic analysis failed before transaction commit",
                        outcome.error,
                    )
                    throw outcome.error
                }
                is KaChangePackageComputer.ApplyOutcome.Success -> {
                    val caretOffsets = LinkedHashMap<FileObject, Int>()
                    val caretPositions = LinkedHashMap<FileObject, javax.swing.text.Position>()
                    outcome.changedFiles.forEach { (path, text) ->
                        val file = sourceByPath[path] ?: FileUtil.toFileObject(FileUtil.normalizeFile(java.io.File(path)))
                            ?: error("Change Kotlin Package could not resolve changed file $path.")
                        val document = current.captureExisting(file)
                        val originalOffset = openCaretOffset(file)?.coerceIn(0, document.length)
                        originalOffset?.let {
                            caretOffsets[file] = it
                            caretPositions[file] = document.createPosition(it)
                        }
                        if (CHANGE_PACKAGE_LOGGER.isLoggable(Level.FINE)) CHANGE_PACKAGE_LOGGER.log(
                            Level.FINE,
                            "Change Package {0}: staging {1}, document={2}@{3}, length={4}, caret={5}",
                            arrayOf(
                                operationId,
                                file.path,
                                document.javaClass.name,
                                System.identityHashCode(document),
                                document.length,
                                originalOffset,
                            ),
                        )
                        current.stageHunkText(file, text, project, originalOffset)
                    }
                    current.commit()
                    caretOffsets.forEach { (file, originalOffset) ->
                        val trackedOffset = caretPositions[file]?.offset ?: originalOffset
                        restoreAfterEditorUpdate {
                            if (CHANGE_PACKAGE_LOGGER.isLoggable(Level.FINE)) CHANGE_PACKAGE_LOGGER.log(
                                Level.FINE,
                                "Change Package {0}: restoring {1} caret from {2} to tracked {3}",
                                arrayOf(operationId, file.path, originalOffset, trackedOffset),
                            )
                            restoreCaret(file, trackedOffset)
                        }
                    }
                    transaction = current
                    undoCaretOffsets = caretOffsets
                    pending = null
                }
            }
        }.onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinChangePackageApplyElement.performChange failed", error) }
        runCatching { pending?.rollback() }
            .onFailure { error -> KotlinLogger.INSTANCE.logException("KotlinChangePackageApplyElement rollback failed", error) }
        KotlinAnalysisAPISession.invalidate(project)
    }

    /**
     * Returns the caret offset from an already-open editor for [file] without opening a new editor.
     *
     * Native undo must restore each modified open editor independently; unopened files do not have a
     * user-visible caret and therefore deliberately receive no restoration edit.
     */
    private fun openCaretOffset(file: FileObject): Int? = runCatching {
        val cookie = DataObject.find(file).lookup.lookup(EditorCookie::class.java) ?: return null
        cookie.openedPanes?.firstOrNull()?.caretPosition
    }.onFailure { error ->
        CHANGE_PACKAGE_LOGGER.log(Level.FINE, "Could not read an open caret for ${file.path}", error)
    }.getOrNull()

    /** Restores all original package directives, usage texts, and open-editor carets. */
    override fun undoChange() {
        runCatching {
            val current = transaction ?: return@runCatching
            val caretOffsets = undoCaretOffsets
            current.undo()
            transaction = null
            undoCaretOffsets = emptyMap()
            caretOffsets.forEach { (file, offset) -> restoreAfterEditorUpdate { restoreCaret(file, offset) } }
            KotlinAnalysisAPISession.invalidate(project)
        }.onFailure { error ->
            KotlinLogger.INSTANCE.logException("KotlinChangePackageApplyElement.undoChange failed", error)
        }
    }
}

private val CHANGE_PACKAGE_LOGGER: Logger = Logger.getLogger("io.github.nbplugins.kotlin.nbm.refactoring.changePackage")
