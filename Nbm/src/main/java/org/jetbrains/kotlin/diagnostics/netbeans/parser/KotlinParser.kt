/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.diagnostics.netbeans.parser

import java.beans.PropertyChangeListener
import java.util.Collections
import javax.swing.event.ChangeListener
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.projectsextensions.KotlinProjectHelper.isScanning
import org.jetbrains.kotlin.resolve.AnalysisResultWithProvider
import org.jetbrains.kotlin.resolve.KotlinAnalyzer
import org.jetbrains.kotlin.utils.ProjectUtils
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.api.java.source.SourceUtils
import org.netbeans.api.project.Project
import org.netbeans.modules.parsing.api.*
import org.netbeans.modules.parsing.spi.*
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject

class KotlinParser : Parser() {

    companion object {
        var file: KtFile? = null
            private set

        var project: Project? = null
            private set

        var analysisResult: AnalysisResultWithProvider? = null

        @JvmStatic fun getAnalysisResult(ktFile: KtFile,
                                         proj: Project) = if (ktFile upToDate file) analysisResult else analyze(ktFile, proj)

        private fun analyze(ktFile: KtFile,
                            proj: Project): AnalysisResultWithProvider? {
            project = proj
            file = ktFile
            return KotlinAnalyzer.analyzeFile(proj, ktFile)
                .also { analysisResult = it }
        }

        private infix fun KtFile.upToDate(ktFile: KtFile?) =
                virtualFile.path == ktFile?.virtualFile?.path && text == ktFile.text

        // Tracks files that already have a save listener registered.
        private val saveListenedPaths: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /**
         * Registers a one-time [DataObject.PROP_MODIFIED] listener for [fo] so that whenever
         * the file transitions from modified → saved (e.g. after Ctrl+Z + Ctrl+S), the K2
         * session is invalidated and the next parse cycle picks up the disk content.
         *
         * Without this, applying a quick fix saves stubs to disk and invalidates the session,
         * but a subsequent Ctrl+Z + Ctrl+S leaves the session valid with the old (fixed) disk
         * content, so the ABSTRACT_MEMBER_NOT_IMPLEMENTED hint never reappears.
         */
        internal fun ensureSaveListener(fo: FileObject, proj: Project) {
            val path = fo.path ?: return
            if (!saveListenedPaths.add(path)) return
            runCatching {
                val dob = DataObject.find(fo)
                val listener = PropertyChangeListener { event ->
                    if (DataObject.PROP_MODIFIED == event.propertyName && event.newValue == false) {
                        KotlinLogger.INSTANCE.logInfo("KotlinParser: file saved ($path), invalidating K2 session")
                        KotlinAnalysisAPISession.invalidate(proj)
                    }
                }
                dob.addPropertyChangeListener(listener)
            }.onFailure {
                // DataObject not available (e.g. in test environment) — safe to ignore.
                saveListenedPaths.remove(path)
            }
        }
    }

    private lateinit var snapshot: Snapshot
    private var cancel = false

    override fun parse(snapshot: Snapshot, task: Task, event: SourceModificationEvent) {
        this.snapshot = snapshot
        cancel = false

        val fo = snapshot.source.fileObject
        KotlinLogger.INSTANCE.logInfo("KotlinParser.parse called for ${fo?.path}")

        if (SourceUtils.isScanInProgress()) {
            KotlinLogger.INSTANCE.logInfo("KotlinParser.parse: scan in progress, skipping ${fo?.path}")
            return
        }

        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
        if (project == null) {
            KotlinLogger.INSTANCE.logWarning("KotlinParser.parse: no project for ${fo?.path}")
            return
        }
        if (project.isScanning()) {
            KotlinLogger.INSTANCE.logInfo("KotlinParser.parse: project scanning, skipping ${fo?.path}")
            return
        }
        if (fo != null) ensureSaveListener(fo, project)
        if (cancel) {
            KotlinLogger.INSTANCE.logInfo("KotlinParser.parse: cancel flag set before analysis, skipping ${fo?.path}")
            return
        }

        try {
            KotlinLogger.INSTANCE.logInfo("KotlinParser.parse: building KtFile for ${fo?.path}")
            val ktFile = ProjectUtils.getKtFile(snapshot.text.toString(), fo)
            KotlinLogger.INSTANCE.logInfo("KotlinParser.parse: starting analysis for ${fo?.path}")
            val result = getAnalysisResult(ktFile, project)
            KotlinLogger.INSTANCE.logInfo("KotlinParser.parse: analysis finished for ${fo?.path}, result=${result != null}")
        } catch (ex: Throwable) {
            KotlinLogger.INSTANCE.logException("KotlinParser.parse failed for ${fo?.path}", ex)
        }
    }

    override fun getResult(task: Task): Result? {
        val taskName = task.javaClass.simpleName
        val project = project
        if (project == null) {
            KotlinLogger.INSTANCE.logWarning("KotlinParser.getResult($taskName): companion.project is null")
            return null
        }
        val foPath = snapshot.source.fileObject?.path
        val filePath = file?.virtualFile?.path
        val ktFile = if (foPath == filePath) file else null
        if (ktFile == null) {
            KotlinLogger.INSTANCE.logWarning("KotlinParser.getResult($taskName): ktFile null (snapshot=$foPath companion=$filePath)")
            return null
        }
        val result = getAnalysisResult(ktFile, project)
        if (result == null) {
            KotlinLogger.INSTANCE.logWarning("KotlinParser.getResult($taskName): analysisResult is null")
            return null
        }

        val kaKtFile = runCatching {
            val wrapper = KotlinAnalysisAPISession.getSession(project)
            if (wrapper.hasDependencies) wrapper.getKtFileForPath(ktFile.virtualFile.path) else null
        }.getOrNull()

        KotlinLogger.INSTANCE.logInfo(
            "KotlinParser.getResult($taskName): returning KotlinParserResult for $foPath " +
            "(kaKtFile=${if (kaKtFile != null) "available" else "null (no deps or not found)"})"
        )
        return KotlinParserResult(snapshot, result, ktFile, snapshot.source.fileObject, project, kaKtFile)
    }

    override fun addChangeListener(changeListener: ChangeListener) {}
    override fun removeChangeListener(changeListener: ChangeListener) {}

    override fun cancel(reason: CancelReason, event: SourceModificationEvent?) {
        cancel = true
        KotlinLogger.INSTANCE.logInfo("Parser cancel ${reason.name}")
    }
}