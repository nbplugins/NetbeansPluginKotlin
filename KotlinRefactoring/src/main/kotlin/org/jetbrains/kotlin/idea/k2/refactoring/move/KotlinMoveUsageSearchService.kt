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
package org.jetbrains.kotlin.idea.k2.refactoring.move

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Identifies a top-level declaration's original direct-import path. */
data class MoveImport(val packageName: FqName, val declarationName: String)

/**
 * Application service: finds every reference to [declaration] across the whole NetBeans project.
 *
 * IDEA's own move engine uses `ReferencesSearch.search()`, backed by `PsiSearchHelper` — a no-op
 * in this plugin's standalone container (see `NoOpPsiSearchHelper`, registered by
 * `KotlinAnalysisAPISession`). The real implementation is registered from `Nbm` (which has access
 * to the full set of `KtFile`s the analysis session knows about) and reuses the same whole-project
 * scan-and-resolve approach already used by Find Usages (E7)'s `KaFindUsagesComputer` — this is a
 * real search, not a stub, just implemented at the layer that has the file list instead of relying
 * on an IntelliJ project index this plugin doesn't build.
 */
interface KotlinMoveUsageSearchService {
    fun findUsages(declaration: KtNamedDeclaration): List<PsiReference>

    /**
     * Finds session files that directly import one of [movedImports]' old declaration paths.
     *
     * @param project IntelliJ project whose standalone K2 session supplies candidate files
     * @param movedImports old package/name pairs mapped to their final package
     * @return Kotlin files whose import directives require explicit non-semantic replacement
     */
    fun findImportingFiles(project: com.intellij.openapi.project.Project, movedImports: Map<MoveImport, FqName>): List<KtFile> = emptyList()

    companion object {
        fun getInstance(): KotlinMoveUsageSearchService? =
            ApplicationManager.getApplication()?.getService(KotlinMoveUsageSearchService::class.java)
    }
}
