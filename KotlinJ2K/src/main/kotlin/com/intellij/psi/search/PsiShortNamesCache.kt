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
package com.intellij.psi.search

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass

/**
 * Standalone replacement for IDEA's `PsiShortNamesCache`.
 *
 * The real cache is backed by the Java short-name index (`java-indexing-impl`), which the
 * standalone session does not build. The J2K engine uses it only in
 * `CanBeShortenedCache.canBeShortened` to decide whether a simple class name is ambiguous.
 *
 * With no index, [processClassesWithName] visits no class, so the caller concludes "not found"
 * and the reference is not shortened here. That is safe: the K2 post-processing step
 * (`K2ShortenReference`) shortens references afterwards using the analysis API.
 */
object PsiShortNamesCache {

    /**
     * Returns the cache for [project].
     *
     * @param project ignored — the standalone replacement is stateless.
     * @return this singleton.
     */
    @JvmStatic
    fun getInstance(project: Project): PsiShortNamesCache = this

    /**
     * Visits the classes whose short name is [name].
     *
     * @param name the short class name to look up.
     * @param processor called for every matching class; returns `false` to stop the search.
     * @param scope ignored — the result is always empty.
     * @param idFilter ignored — the result is always empty.
     * @return `true` if processing completed (i.e. always `true`, since nothing is visited).
     */
    fun processClassesWithName(
        name: String,
        processor: (PsiClass) -> Boolean,
        scope: GlobalSearchScope,
        idFilter: PsiClass?
    ): Boolean = true
}
