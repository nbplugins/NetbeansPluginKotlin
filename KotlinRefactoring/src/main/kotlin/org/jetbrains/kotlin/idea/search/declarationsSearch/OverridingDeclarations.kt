// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search.declarationsSearch

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Stub: iterates over overriding declarations.
 * In standalone NetBeans mode returns no overriders (full hierarchy search is not available).
 */
fun KtDeclaration.forEachOverridingElement(
    scope: com.intellij.psi.search.SearchScope? = null,
    processor: (superDeclaration: KtDeclaration, overridingDeclaration: PsiElement) -> Boolean
) {
    // No overrider search in standalone mode.
}
