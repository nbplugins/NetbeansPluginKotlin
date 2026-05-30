/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.installer

import org.jetbrains.kotlin.project.KotlinSources
import org.jetbrains.kotlin.installer.KotlinUpdater
import io.github.nbplugins.kotlin.nbm.highlighter.KaSemanticHighlightingVisitor
import io.github.nbplugins.kotlin.nbm.highlighter.KotlinSemanticHighlightsLayerFactory
import io.github.nbplugins.kotlin.nbm.hover.KotlinTooltipHighlightsLayerFactory
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper
import org.netbeans.modules.parsing.api.Source
import org.netbeans.modules.parsing.api.indexing.IndexingManager
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.windows.TopComponent
import org.openide.windows.WindowManager
import org.jetbrains.kotlin.utils.ProjectUtils
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper.isMavenProject
import io.github.nbplugins.kotlin.nbm.projectsextensions.maven.MavenHelper
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.log.KotlinLogger
import org.openide.modules.ModuleInstall
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class KotlinInstaller : ModuleInstall() {

    override fun restored() {
        FakeIntellijHome.StartingUp().run()
        KotlinAnalysisAPISession.initApplicationEnvironment()
        WindowManager.getDefault().invokeWhenUIReady {
            ProjectUtils.checkKtHome()
            // Pre-warm sessions for .kt files already open at startup (restored from previous session).
            // Their "opened" events fired before our listener was registered, so handle them explicitly.
            WindowManager.getDefault().registry.opened
                .filterIsInstance<TopComponent>()
                .mapNotNull { tc -> tc.lookup?.lookup(DataObject::class.java)?.primaryFile }
                .filter { it.mimeType == "text/x-kotlin" }
                .forEach { file ->
                    KotlinLogger.INSTANCE.logInfo("[TIME] KotlinInstaller: .kt file already open at startup ${file.path} at ${now()}")
                    checkProjectConfiguration(file)
                    val proj = ProjectUtils.getKotlinProjectForFileObject(file) ?: return@forEach
                    org.openide.util.RequestProcessor.getDefault().post {
                        preAnalyzeFile(file, proj)
                    }
                }
            WindowManager.getDefault().registry.addPropertyChangeListener listener@{
                if (it.propertyName == "opened") {
                    val newHashSet = it.newValue as HashSet<*>
                    val oldHashSet = it.oldValue as HashSet<*>
                    newHashSet.filter {!oldHashSet.contains(it)}
                            .forEach {
                                val dataObject = (it as? TopComponent)?.lookup?.lookup(DataObject::class.java) ?: return@forEach
                                val currentFile = dataObject.primaryFile
                                if (currentFile != null) {
                                    if (currentFile.mimeType == "text/x-kotlin") {
                                        KotlinLogger.INSTANCE.logInfo(
                                            "[TIME] KotlinInstaller: .kt editor opened ${currentFile.path} at ${now()}"
                                        )
                                        checkUpdates()
                                        checkProjectConfiguration(currentFile)
                                        // Pre-warm the K2 session on a background thread so it is
                                        // ready before EDITOR_SENSITIVE_TASK_SCHEDULER fires the parser.
                                        val proj = ProjectUtils.getKotlinProjectForFileObject(currentFile)
                                        if (proj != null) {
                                            org.openide.util.RequestProcessor.getDefault().post {
                                                KotlinAnalysisAPISession.getSession(proj)
                                            }
                                        }
                                    }
                                    if (currentFile.mimeType == "text/x-java") {
                                        checkVirtualSourceProvider(currentFile)
                                    }
                                }
                    }
                }
            }
        }
    }
    
    /**
     * Builds the K2 session for [proj] and runs semantic analysis on [file] directly, without
     * waiting for [org.netbeans.modules.parsing.spi.Scheduler.EDITOR_SENSITIVE_TASK_SCHEDULER].
     * Called on a background thread for `.kt` files restored from the previous NetBeans session so
     * that highlights are ready before the user clicks on the tab.
     */
    private fun preAnalyzeFile(file: FileObject, proj: org.netbeans.api.project.Project) {
        runCatching {
            val session = KotlinAnalysisAPISession.getSession(proj)
            val kaKtFile = session.getKtFileForPath(file.path) ?: return
            val highlights = KaSemanticHighlightingVisitor(kaKtFile).computeHighlightingRanges()
            val doc = Source.create(file).getDocument(true) ?: return
            KotlinSemanticHighlightsLayerFactory.applyHighlights(doc, highlights)
            KotlinTooltipHighlightsLayerFactory.applyTooltipRanges(doc, highlights.keys)
            KotlinLogger.INSTANCE.logInfo(
                "[TIME] KotlinInstaller: startup pre-analysis applied for ${file.path} at ${now()} (${highlights.size} ranges)"
            )
        }.onFailure { ex ->
            KotlinLogger.INSTANCE.logWarning("Startup pre-analysis failed for ${file.path}:\n${ex.stackTraceToString()}")
        }
    }

    private fun checkVirtualSourceProvider(file: FileObject) {
        val project = ProjectUtils.getKotlinProjectForFileObject(file) ?: return
        if (!KotlinProjectHelper.hasJavaFiles(project)) {
            KotlinProjectHelper.setHasJavaFiles(project)
            KotlinSources(project).getAllKtFiles().forEach {
                IndexingManager.getDefault().refreshAllIndices(it)
            }
        }
    }
    
    private fun checkProjectConfiguration(file: FileObject) {
        val project = ProjectUtils.getKotlinProjectForFileObject(file) ?: return
        if (project.isMavenProject()) MavenHelper.configure(project)
    }
    
    private fun checkUpdates() {
        if (!KotlinUpdater.updated) KotlinUpdater.checkUpdates()
    }

    private fun now(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
}