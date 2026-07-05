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
package org.jetbrains.kotlin.idea.core

import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.base.util.quoteIfNeeded
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

/**
 * Real (not faked) but standalone-safe replacements for the subset of IDEA's
 * `plugins/kotlin/frontend-independent/src/org/jetbrains/kotlin/idea/core/packageUtils.kt` that
 * Move Declaration (E9.7) needs. The full upstream file is not portable standalone: it pulls in
 * `ModuleRootManager`, `ExternalSystemContentRootContributor`, `KotlinFacet`,
 * `PerModulePackageCacheService`, etc. — the entire IntelliJ project/module/root system.
 *
 * "Implicit package prefix" is a Gradle/Android-specific feature (a source root whose package
 * declarations don't match its directory structure). NetBeans projects (Maven/Gradle/Ant) never
 * have one, so [getImplicitPackagePrefix] always returning `null` is the correct answer for every
 * project type this plugin supports, not a simplification of real behaviour.
 */

private fun PsiDirectory.getNonRootFqNameOrNull(): FqName? =
    JavaDirectoryService.getInstance()?.getPackage(this)?.qualifiedName?.let(::FqName)

fun PsiDirectory.getFqNameByDirectoryOrRoot(): FqName = getNonRootFqNameOrNull() ?: FqName.ROOT

fun PsiDirectory.getImplicitPackagePrefix(): FqName? = null

fun PsiDirectory.getFqNameWithImplicitPrefix(): FqName? = getNonRootFqNameOrNull()

fun PsiDirectory.getFqNameWithImplicitPrefixOrRoot(): FqName = getFqNameWithImplicitPrefix() ?: FqName.ROOT

/** Real port of the directory-based FqName lookup, minus the single-file-source-root tracker (IDE-only, Gradle/Android). */
fun PsiFile.getFqNameByDirectory(): FqName = containingDirectory?.getFqNameByDirectoryOrRoot() ?: FqName.ROOT

fun createKotlinFile(
    fileName: String,
    targetDir: PsiDirectory,
    packageName: String? = targetDir.getFqNameWithImplicitPrefix()?.asString()
): KtFile {
    targetDir.checkCreateFile(fileName)
    val packageFqName = packageName?.let(::FqName) ?: FqName.ROOT
    val file = PsiFileFactory.getInstance(targetDir.project).createFileFromText(
        fileName, KotlinFileType.INSTANCE,
        if (!packageFqName.isRoot) "package ${packageFqName.quoteIfNeeded().asString()} \n\n" else ""
    )
    return targetDir.add(file) as KtFile
}
