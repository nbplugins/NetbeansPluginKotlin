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
package org.jetbrains.kotlin.filesystem

import io.github.nbplugins.kotlin.nbm.filesystem.KaLightClassGenerator
import java.io.File
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParser
import org.jetbrains.kotlin.model.KotlinEnvironment
import org.jetbrains.kotlin.resolve.AnalysisResultWithProvider
import org.jetbrains.kotlin.resolve.NetBeansAnalyzerFacadeForJVM
import org.jetbrains.kotlin.filesystem.lightclasses.KotlinLightClassGeneration
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.projectsextensions.KotlinProjectHelper
import org.jetbrains.kotlin.utils.ProjectUtils
import org.jetbrains.kotlin.utils.hasMain
import org.netbeans.api.project.Project
import org.netbeans.modules.java.preprocessorbridge.spi.VirtualSourceProvider
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil

fun translate(files: Iterable<File>, result: VirtualSourceProvider.Result) {
    KotlinLogger.INSTANCE.logInfo("KotlinVirtualSourceProvider translate $files")

    val filesToTranslate = if (files.firstOrNull().skipTranslating()) {
        KotlinLogger.INSTANCE.logInfo("No java files. Translating only kt files with main functions")
        files.mapNotNull { FileUtil.toFileObject(FileUtil.normalizeFile(it)) }
                .filter { ProjectUtils.getKtFile(it).hasMain() }
    } else files.mapNotNull { FileUtil.toFileObject(FileUtil.normalizeFile(it)) }

    val project = filesToTranslate.firstOrNull()?.let { ProjectUtils.getKotlinProjectForFileObject(it) } ?: return

    if (filesToTranslate.size == KotlinPsiManager.getFilesByProject(project, false).size) {
        if (KotlinVirtualSourceProvider.isFullyTranslated(project)) return else KotlinVirtualSourceProvider.translated(project)
        val startTime = System.nanoTime()
        val analysisResult = NetBeansAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                project, KotlinEnvironment.getEnvironment(project).project,
                ProjectUtils.getSourceFilesWithDependencies(project))
        KotlinLogger.INSTANCE.logInfo("Kotlin analysis took ${(System.nanoTime() - startTime)}")
        filesToTranslate.translate(result, analysisResult, project)
        return
    }

    filesToTranslate.translate(result, proj = project)
}

private fun List<FileObject>.translate(result: VirtualSourceProvider.Result,
                                       analysisResult: AnalysisResultWithProvider? = null,
                                       proj: Project? = null) {
    forEach { file ->
        // K2 primary path: generate Java stubs directly from K2 symbols when session is available.
        val project = proj ?: ProjectUtils.getKotlinProjectForFileObject(file)
        if (project != null) {
            val k2Stubs = KaLightClassGenerator.getJavaStubs(file, project)
            if (k2Stubs != null) {
                val ktFile = ProjectUtils.getKtFile(file)
                val packageName = ktFile?.packageFqName?.asString()?.replace(".", "/") ?: ""
                k2Stubs.forEach { (className, code) ->
                    result.add(FileUtil.toFile(file), packageName, className, code)
                }
                return@forEach
            }
        }
        // K1 fallback: compile to bytecode and decompile via ASM.
        val byteCode = file.byteCode(analysisResult, proj)
        if (byteCode.isNotEmpty()) {
            val stubs = JavaStubGenerator.gen(byteCode)
            stubs.forEach { (classNode, code) ->
                val packageName = classNode.name.substringBeforeLast("/")
                result.add(FileUtil.toFile(file), packageName, file.name, code)
            }
        }
    }
}

private fun File?.skipTranslating(): Boolean {
    if (this == null) return true

    val normalizedFile = FileUtil.normalizeFile(this)
    val fo = FileUtil.toFileObject(normalizedFile) ?: return false
    val project = ProjectUtils.getKotlinProjectForFileObject(fo) ?: return false

    return !KotlinProjectHelper.hasJavaFiles(project)
}

private fun FileObject.byteCode(result: AnalysisResultWithProvider? = null,
                                proj: Project? = null): List<ByteArray> {
    val project = proj ?: ProjectUtils.getKotlinProjectForFileObject(this) ?: return emptyList()
    val ktFile = ProjectUtils.getKtFile(this) ?: return emptyList()
    val analysisResult = result ?: KotlinParser.getAnalysisResult(ktFile, project) ?: return emptyList()

    return KotlinLightClassGeneration.getByteCode(this, project, analysisResult.analysisResult)
}

