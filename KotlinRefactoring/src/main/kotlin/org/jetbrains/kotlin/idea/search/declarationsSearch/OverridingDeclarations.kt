// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search.declarationsSearch

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.searching.inheritors.StandaloneInheritorSearch
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Iterates K2-resolved declarations that override this declaration in the active standalone session.
 *
 * @param scope retained for IntelliJ API compatibility; the active NetBeans build session defines
 * the searchable source scope.
 * @param processor receives this declaration and each overriding declaration; returning `false`
 * stops iteration.
 */
fun KtDeclaration.forEachOverridingElement(
    scope: com.intellij.psi.search.SearchScope? = null,
    processor: (superDeclaration: KtDeclaration, overridingDeclaration: PsiElement) -> Boolean
) {
    for (overriding in StandaloneInheritorSearch.searchOverriderElements(this, true)) {
        if (!processor(this, overriding)) return
    }
}
