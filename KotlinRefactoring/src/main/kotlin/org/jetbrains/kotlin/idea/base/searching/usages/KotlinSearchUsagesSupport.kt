package org.jetbrains.kotlin.idea.base.searching.usages

import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Stub for IDE-only KotlinSearchUsagesSupport.
 * In standalone mode, super-method lookup returns empty — this is safe for inline refactoring.
 */
object KotlinSearchUsagesSupport {
    object SearchUtils {
        /** Stub: returns empty list — super methods not resolvable in standalone K2 session. */
        fun findSuperMethodsNoWrapping(element: KtDeclaration): List<KtDeclaration> = emptyList()

        fun findSuperMethodsNoWrapping(element: Any?): List<Any> = emptyList()
    }
}
