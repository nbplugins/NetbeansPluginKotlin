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
package org.jetbrains.kotlin.idea.searching.inheritors

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtClass

/**
 * Stub of IntelliJ's `org.jetbrains.kotlin.idea.searching.inheritors.DirectKotlinClassInheritorsSearch`:
 * a whole-project class-inheritance search index does not exist standalone, same limitation as
 * [org.jetbrains.kotlin.idea.search.declarationsSearch.HierarchySearchRequest]. Move Declaration's
 * sealed-class-hierarchy conflict check degrades gracefully: with no known inheritors it simply
 * never reports the "broken sealed hierarchy" conflict, rather than falsely flagging one.
 */
object DirectKotlinClassInheritorsSearch {
    fun search(ktClass: KtClass): Sequence<PsiElement> = emptySequence()
}
