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

import javax.swing.event.ChangeListener
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
                            proj: Project): AnalysisResultWithProvider? = KotlinAnalyzer.analyzeFile(proj, ktFile)
                .also { 
                    project = proj 
                    file = ktFile
                    analysisResult = it
                }
        
        private infix fun KtFile.upToDate(ktFile: KtFile?) = 
                virtualFile.path == ktFile?.virtualFile?.path && text == ktFile.text
        
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

        KotlinLogger.INSTANCE.logInfo("KotlinParser.getResult($taskName): returning KotlinParserResult for $foPath")
        return KotlinParserResult(snapshot, result, ktFile, snapshot.source.fileObject, project)
    }

    override fun addChangeListener(changeListener: ChangeListener) {}
    override fun removeChangeListener(changeListener: ChangeListener) {}

    override fun cancel(reason: CancelReason, event: SourceModificationEvent?) {
        cancel = true
        KotlinLogger.INSTANCE.logInfo("Parser cancel ${reason.name}")
    }
}