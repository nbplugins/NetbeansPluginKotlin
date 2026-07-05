/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
package org.jetbrains.kotlin.idea.base.projectStructure

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.psi.KtElement

/**
 * Real (not faked) replacement for IDEA's IDE-only `Module.toKaSourceModuleContainingElement` /
 * `PsiElement.getKaModuleOfTypeSafe` / `PsiElement.getKaModule` (from
 * `plugins/kotlin/base/project-structure/src/org/jetbrains/kotlin/idea/base/projectStructure/api.kt`),
 * which resolve a [KaModule] by looking up [element] in IntelliJ's project/module index — an index
 * this plugin's standalone `StandaloneAnalysisAPISession` never builds (see
 * [[project_kotlin_compiler_shaded_versions]]).
 *
 * This plugin's analysis session always registers exactly one source [KaModule] per NetBeans
 * project (see `KotlinAnalysisAPISession`), so instead of an index lookup, the *only* correct
 * answer is "the module that resolved [element] in the first place" — which
 * `KaSession.useSiteModule` already gives us directly, with no bridge to a separate module index
 * needed at all.
 */
@OptIn(KaAllowAnalysisOnEdt::class, KaAllowAnalysisFromWriteAction::class)
private fun kaModuleOf(element: PsiElement): KaModule? = runCatching {
    val ktElement = element as? KtElement ?: return null
    allowAnalysisOnEdt {
        allowAnalysisFromWriteAction {
            analyze(ktElement) { useSiteModule }
        }
    }
}.getOrNull()

fun Module.toKaSourceModuleContainingElement(element: PsiElement): KaModule? = kaModuleOf(element)

@Suppress("UNCHECKED_CAST")
fun <T : KaModule> PsiElement.getKaModuleOfTypeSafe(project: Project, useSiteModule: KaModule?): T? =
    kaModuleOf(this) as? T

fun PsiElement.getKaModule(project: Project, useSiteModule: KaModule?): KaModule =
    kaModuleOf(this) ?: error("Could not resolve KaModule for $this")
