package org.jetbrains.kotlin.idea.base.searching.usages

import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch

/** Stub: delegates to standard ReferencesSearch. */
object ReferencesSearchScopeHelper {
    fun search(element: PsiElement, scope: SearchScope) =
        ReferencesSearch.search(element, scope)
}
