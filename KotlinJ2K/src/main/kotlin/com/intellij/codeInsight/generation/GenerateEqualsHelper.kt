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
package com.intellij.codeInsight.generation

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.MethodSignature
import com.intellij.psi.util.MethodSignatureUtil

/**
 * Standalone replacement for IDEA's `GenerateEqualsHelper`.
 *
 * Only the [getEqualsSignature] helper used by the J2K engine (`nj2k/psiUtils.kt`) is provided.
 * The real class also generates `equals`/`hashCode` implementations, which the converter never
 * needs.
 */
object GenerateEqualsHelper {

    /**
     * Builds the signature of `boolean equals(Object)` in [scope].
     *
     * @param project the project that owns the PSI.
     * @param scope the scope in which `java.lang.Object` is resolved.
     * @return the `equals(Object)` method signature.
     */
    @JvmStatic
    fun getEqualsSignature(project: Project, scope: GlobalSearchScope): MethodSignature =
        MethodSignatureUtil.createMethodSignature(
            "equals",
            arrayOf<PsiType>(PsiType.getJavaLangObject(PsiManager.getInstance(project), scope)),
            PsiTypeParameter.EMPTY_ARRAY,
            PsiSubstitutor.EMPTY
        )
}
