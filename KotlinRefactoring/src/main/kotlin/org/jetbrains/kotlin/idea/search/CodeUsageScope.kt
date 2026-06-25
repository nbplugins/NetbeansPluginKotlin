// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

/** Stub: returns the whole-project scope for code usage searches in standalone NetBeans mode. */
fun PsiElement.codeUsageScopeRestrictedToProject(): GlobalSearchScope =
    GlobalSearchScope.allScope(project)
