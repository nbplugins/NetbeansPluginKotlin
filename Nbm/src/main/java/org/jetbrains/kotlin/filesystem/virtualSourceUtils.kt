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
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.projectsextensions.KotlinProjectHelper
import org.jetbrains.kotlin.utils.ProjectUtils
import org.jetbrains.kotlin.utils.hasMain
import org.netbeans.api.project.Project
import org.netbeans.modules.java.preprocessorbridge.spi.VirtualSourceProvider
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil

/**
 * Translates Kotlin source files to Java virtual sources (stubs) using the K2 Analysis API.
 * Called by the NetBeans Java infrastructure to make Kotlin declarations visible as Java types.
 */
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
    }

    filesToTranslate.translate(result, project)
}

private fun List<FileObject>.translate(result: VirtualSourceProvider.Result, proj: Project) {
    forEach { file ->
        val project = ProjectUtils.getKotlinProjectForFileObject(file) ?: proj
        val k2Stubs = KaLightClassGenerator.getJavaStubs(file, project)
        if (k2Stubs != null) {
            val ktFile = ProjectUtils.getKtFile(file)
            val packageName = ktFile?.packageFqName?.asString()?.replace(".", "/") ?: ""
            k2Stubs.forEach { (className, code) ->
                result.add(FileUtil.toFile(file), packageName, className, code)
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
