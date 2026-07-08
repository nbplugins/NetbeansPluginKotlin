// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Stub: IDEA-specific search utilities not needed in NetBeans standalone mode.
 *
 * [isOverridable]/[isCallableOverride] are used by the ported Change Signature engine (E9.8) to
 * decide whether a member is a candidate override target. There is no project-wide inheritor
 * index standalone (same limitation already accepted for Move Declaration's hierarchy-based
 * conflict checks), so [isCallableOverride] conservatively returns false: an override relationship
 * can only be confirmed with such an index, and a false positive here would apply an unrelated
 * declaration's signature change.
 */
object KotlinSearchUsagesSupport {
    @JvmStatic
    fun getInstance(project: Project): KotlinSearchUsagesSupport = this

    object SearchUtils {
        /** In IDEA finds super-methods; stub returns empty (no hierarchy search in standalone mode). */
        fun findSuperMethodsNoWrapping(element: PsiElement): List<PsiElement> = emptyList()

        /** In IDEA checks whether [declaration] can be overridden; stub always allows it. */
        fun KtDeclaration.isOverridable(): Boolean = true
    }

    /** See class KDoc: no whole-project inheritor index standalone, so this always returns false. */
    fun isCallableOverride(declaration: KtDeclaration, candidate: PsiNamedElement): Boolean = false
}
