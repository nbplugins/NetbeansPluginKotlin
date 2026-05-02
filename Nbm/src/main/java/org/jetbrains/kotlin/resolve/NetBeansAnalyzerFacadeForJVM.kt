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
package org.jetbrains.kotlin.resolve

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.jvm.compiler.CliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.model.KotlinEnvironment
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.project.Project as NBProject
import com.intellij.openapi.project.Project

object NetBeansAnalyzerFacadeForJVM {

    fun analyzeFilesWithJavaIntegration(
            kotlinProject: NBProject,
            project: Project,
            filesToAnalyze: Collection<KtFile>): AnalysisResultWithProvider {
        val filesSet = filesToAnalyze.toSet()

        val allFiles = linkedSetOf<KtFile>()
        allFiles.addAll(filesSet)
        val addedFiles = filesSet.mapNotNullTo(hashSetOf()) { getPath(it) }
        ProjectUtils.getSourceFilesWithDependencies(kotlinProject).filterNotTo(allFiles) {
            getPath(it) in addedFiles
        }

        val environment = KotlinEnvironment.getEnvironment(kotlinProject)
        val configuration = environment.configuration
        val trace = CliBindingTrace()

        val packagePartProviderFn: (GlobalSearchScope) -> PackagePartProvider = { _ ->
            KotlinPackagePartProvider(kotlinProject)
        }
        val declarationProviderFn: (org.jetbrains.kotlin.storage.StorageManager, Collection<KtFile>) -> FileBasedDeclarationProviderFactory = { sm, files ->
            FileBasedDeclarationProviderFactory(sm, files)
        }

        val container = TopDownAnalyzerFacadeForJVM.createContainer(
                project, allFiles, trace, configuration,
                packagePartProviderFn, declarationProviderFn,
                CompilerEnvironment
        )

        val analysisResult = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                project, allFiles, trace, configuration,
                packagePartProviderFn, declarationProviderFn
        )

        return AnalysisResultWithProvider(analysisResult, container)
    }

    private fun getPath(jetFile: KtFile): String? = jetFile.virtualFile?.path
}
