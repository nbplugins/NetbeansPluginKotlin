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
package org.jetbrains.kotlin.idea.k2.refactoring.changeSignature

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

/**
 * Application service: finds every reference to [element] across the whole NetBeans project.
 *
 * Mirrors [org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService] (E9.7):
 * IDEA's ported Change Signature engine uses `ReferencesSearch`/`MethodReferencesSearch`, backed by
 * `PsiSearchHelper` — a no-op in this plugin's standalone container. The real implementation is
 * registered from `Nbm` and reuses the same whole-project scan-and-resolve approach already used by
 * Find Usages (E7)'s `KaFindUsagesComputer` and Move Declaration's search service.
 *
 * [element] may be the changed declaration itself, or one of its value parameters/primary-
 * constructor properties (Change Signature also needs to retarget parameter-name references).
 */
interface KotlinChangeSignatureUsageSearchService {
    fun findUsages(element: PsiElement): List<PsiReference>

    companion object {
        fun getInstance(): KotlinChangeSignatureUsageSearchService? =
            ApplicationManager.getApplication()?.getService(KotlinChangeSignatureUsageSearchService::class.java)
    }
}
