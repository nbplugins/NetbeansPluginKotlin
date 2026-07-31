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
package org.jetbrains.kotlin.idea.search.declarationsSearch

import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.util.CollectionQuery
import com.intellij.util.Query
import org.jetbrains.kotlin.idea.searching.inheritors.StandaloneInheritorSearch

/**
 * Compatibility representation of IntelliJ's hierarchy-search request.
 *
 * IDEA normally resolves this request through its indexes. The standalone bridge delegates to a
 * registered K2-backed search service, which is populated by the NetBeans build-scoped session.
 * This preserves processor control flow while substituting only the unavailable index layer.
 */
class HierarchySearchRequest<T : PsiElement>(
    val original: T,
    val searchScope: SearchScope,
    val searchDeeply: Boolean = false,
)

/** Returns direct or transitive K2-backed inheritors for this request. */
fun HierarchySearchRequest<*>.searchInheritors(): Query<PsiElement> =
    CollectionQuery(StandaloneInheritorSearch.searchElements(original, searchDeeply).toList())

/** Returns K2-backed overriding declarations for this request. */
fun HierarchySearchRequest<*>.searchOverriders(): Sequence<PsiElement> =
    StandaloneInheritorSearch.searchOverriderElements(original, searchDeeply)
