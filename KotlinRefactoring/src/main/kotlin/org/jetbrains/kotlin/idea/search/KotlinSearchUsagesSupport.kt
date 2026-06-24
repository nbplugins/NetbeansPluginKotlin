// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search

import com.intellij.psi.PsiElement

/** Stub: IDEA-specific search utilities not needed in NetBeans standalone mode. */
object KotlinSearchUsagesSupport {
    object SearchUtils {
        /** In IDEA finds super-methods; stub returns empty (no hierarchy search in standalone mode). */
        fun findSuperMethodsNoWrapping(element: PsiElement): List<PsiElement> = emptyList()
    }
}
