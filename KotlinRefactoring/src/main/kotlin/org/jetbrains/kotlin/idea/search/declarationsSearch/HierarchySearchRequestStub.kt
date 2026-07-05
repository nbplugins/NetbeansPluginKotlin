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

/**
 * Stub of IntelliJ's `com.intellij.psi.search.searches.HierarchySearchRequest` /
 * `org.jetbrains.kotlin.idea.search.declarationsSearch.searchOverriders`: a whole-project
 * class-hierarchy search index does not exist standalone. Consistent with the already-established
 * precedent in this codebase for the same limitation
 * ([OverridingDeclarations.forEachOverridingElement][forEachOverridingElement], added for E8/E9
 * navigation features), returns no overriders — Move Declaration's "is this overridden in a
 * subclass" conflict check is skipped rather than falsely reported, same tradeoff already accepted
 * elsewhere in this plugin.
 */
class HierarchySearchRequest(val original: PsiElement, val searchScope: SearchScope, val searchDeeply: Boolean)

fun HierarchySearchRequest.searchOverriders(): Sequence<PsiElement> = emptySequence()
