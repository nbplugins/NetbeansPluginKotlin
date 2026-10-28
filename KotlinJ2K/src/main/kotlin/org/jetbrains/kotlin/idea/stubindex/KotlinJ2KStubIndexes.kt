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
package org.jetbrains.kotlin.idea.stubindex

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Standalone stubs for the four Kotlin name indexes the J2K engine consults while resolving
 * fully-qualified names (`org.jetbrains.kotlin.nj2k.JKResolver`).
 *
 * IDEA builds these indexes from the project's Kotlin source files. This plugin's standalone
 * analysis session does not register stub indexes, so every lookup would fail. Returning an empty
 * collection makes `JKResolver` fall through to its **import-directive resolution path**
 * (`constructImportDirectiveWithContext` + `PsiReference.resolve`), which is the same fallback an
 * IDE uses when the index has no hit. Documented limitation: top-level Kotlin declarations that are
 * only reachable through an index (and not through an import directive) are not resolved.
 */
object KotlinFullClassNameIndex {

    /**
     * Returns Kotlin classes and objects with the given fully-qualified name.
     *
     * @param fqName the fully-qualified class name.
     * @param project the project used for resolution.
     * @param scope the search scope.
     * @return always an empty collection — see the class documentation.
     */
    @JvmStatic
    operator fun get(
        fqName: String,
        project: Project,
        scope: GlobalSearchScope
    ): Collection<KtClassOrObject> = emptyList()
}

/**
 * Standalone stub for IDEA's `KotlinTopLevelTypeAliasFqNameIndex`; see
 * [KotlinFullClassNameIndex] for the rationale.
 */
object KotlinTopLevelTypeAliasFqNameIndex {

    /**
     * Returns top-level type aliases with the given fully-qualified name.
     *
     * @param fqName the fully-qualified type-alias name.
     * @param project the project used for resolution.
     * @param scope the search scope.
     * @return always an empty collection — see [KotlinFullClassNameIndex].
     */
    @JvmStatic
    operator fun get(
        fqName: String,
        project: Project,
        scope: GlobalSearchScope
    ): Collection<KtTypeAlias> = emptyList()
}

/**
 * Standalone stub for IDEA's `KotlinTopLevelFunctionFqnNameIndex`; see
 * [KotlinFullClassNameIndex] for the rationale.
 */
object KotlinTopLevelFunctionFqnNameIndex {

    /**
     * Returns top-level functions with the given fully-qualified name.
     *
     * @param fqName the fully-qualified function name.
     * @param project the project used for resolution.
     * @param scope the search scope.
     * @return always an empty collection — see [KotlinFullClassNameIndex].
     */
    @JvmStatic
    operator fun get(
        fqName: String,
        project: Project,
        scope: GlobalSearchScope
    ): Collection<KtNamedFunction> = emptyList()
}

/**
 * Standalone stub for IDEA's `KotlinTopLevelPropertyFqnNameIndex`; see
 * [KotlinFullClassNameIndex] for the rationale.
 */
object KotlinTopLevelPropertyFqnNameIndex {

    /**
     * Returns top-level properties with the given fully-qualified name.
     *
     * @param fqName the fully-qualified property name.
     * @param project the project used for resolution.
     * @param scope the search scope.
     * @return always an empty collection — see [KotlinFullClassNameIndex].
     */
    @JvmStatic
    operator fun get(
        fqName: String,
        project: Project,
        scope: GlobalSearchScope
    ): Collection<KtProperty> = emptyList()
}
