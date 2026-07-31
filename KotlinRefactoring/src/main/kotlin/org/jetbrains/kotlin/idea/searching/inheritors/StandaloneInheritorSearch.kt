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
 * Application bridge replacing IDEA's indexed inheritor and overrider searches.
 *
 * The copied IDEA refactoring processors retain their normal hierarchy-search calls. NetBeans
 * installs a K2-aware implementation for each standalone analysis session, which supplies the
 * build-wide source results without changing the upstream processor algorithms.
 */
interface StandaloneInheritorSearch {
    /** Finds inheritors of [element], optionally including transitive descendants. */
    fun search(element: PsiElement, deep: Boolean): Sequence<PsiElement>

    /** Finds overriding declarations of [element], optionally including transitive overrides. */
    fun searchOverriders(element: PsiElement, deep: Boolean): Sequence<PsiElement>

    companion object {
        @Volatile private var implementation: StandaloneInheritorSearch = Empty

        /** Installs the active standalone search implementation. */
        @JvmStatic
        fun install(search: StandaloneInheritorSearch) {
            implementation = search
        }

        /** Delegates inheritor lookup to the installed implementation. */
        @JvmStatic
        fun searchElements(element: PsiElement, deep: Boolean): Sequence<PsiElement> = implementation.search(element, deep)

        /** Delegates override lookup to the installed implementation. */
        @JvmStatic
        fun searchOverriderElements(element: PsiElement, deep: Boolean): Sequence<PsiElement> =
            implementation.searchOverriders(element, deep)
    }

    /** Safe default before NetBeans installs a session-backed search service. */
    private data object Empty : StandaloneInheritorSearch {
        override fun search(element: PsiElement, deep: Boolean): Sequence<PsiElement> = emptySequence()
        override fun searchOverriders(element: PsiElement, deep: Boolean): Sequence<PsiElement> = emptySequence()
    }
}

/**
 * Compatibility facade used by existing IDEA sources that search direct Kotlin class inheritors.
 *
 * @param ktClass source class.
 * @return direct inheritors supplied by the installed standalone search.
 */
object DirectKotlinClassInheritorsSearch {
    @JvmStatic
    fun search(ktClass: KtClass): Sequence<PsiElement> = StandaloneInheritorSearch.searchElements(ktClass, false)
}
