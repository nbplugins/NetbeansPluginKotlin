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
package com.intellij.psi.search.searches

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.SearchScope
import com.intellij.util.CollectionQuery
import com.intellij.util.Query

/**
 * Standalone replacements for the IDE-only class/method hierarchy searches.
 *
 * `ClassInheritorsSearch` and `OverridingMethodsSearch` live in `java-indexing-impl`, which this
 * plugin does not bundle; the standalone session has no Java class index.
 *
 * Both return an empty query. The J2K converter uses them only as *safety checks* — "does this
 * class have subclasses?", "is this method overridden?" — so an empty result means "no" and the
 * converter proceeds as if the declaration were free-standing. The produced Kotlin stays valid;
 * at worst a getter that is also overridden in an external subclass is converted to a property
 * without a custom setter.
 */

/**
 * Standalone replacement for IDEA's `ClassInheritorsSearch`.
 */
object ClassInheritorsSearch {

    /**
     * Finds the classes inheriting [aClass].
     *
     * @param aClass the class to search for.
     * @return always an empty query — no Java index is available in standalone mode.
     */
    @JvmStatic
    fun search(aClass: PsiClass): Query<PsiClass> = search(aClass, false)

    /**
     * Finds the classes inheriting [aClass].
     *
     * @param aClass the class to search for.
     * @param checkDeep ignored — the result is always empty.
     * @return always an empty query — see the class documentation.
     */
    @JvmStatic
    fun search(aClass: PsiClass, checkDeep: Boolean): Query<PsiClass> =
        CollectionQuery(emptyList())
}

/**
 * Standalone replacement for IDEA's `OverridingMethodsSearch`.
 */
object OverridingMethodsSearch {

    /**
     * Finds the methods overriding [method].
     *
     * @param method the method to search for.
     * @return always an empty query — no Java index is available in standalone mode.
     */
    @JvmStatic
    fun search(method: PsiMethod): Query<PsiMethod> = search(method, false)

    /**
     * Finds the methods overriding [method].
     *
     * @param method the method to search for.
     * @param checkDeep ignored — the result is always empty.
     * @return always an empty query — see the class documentation.
     */
    @JvmStatic
    fun search(method: PsiMethod, checkDeep: Boolean): Query<PsiMethod> =
        CollectionQuery(emptyList())

    /**
     * Finds the methods overriding [method] inside [scope].
     *
     * @param method the method to search for.
     * @param scope ignored — the result is always empty.
     * @param checkDeep ignored — the result is always empty.
     * @return always an empty query — see the class documentation.
     */
    @JvmStatic
    fun search(method: PsiMethod, scope: SearchScope, checkDeep: Boolean): Query<PsiMethod> =
        CollectionQuery(emptyList())
}
