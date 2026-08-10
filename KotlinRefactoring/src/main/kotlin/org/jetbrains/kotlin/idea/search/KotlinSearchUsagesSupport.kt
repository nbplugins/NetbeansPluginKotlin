// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.idea.searching.inheritors.StandaloneInheritorSearch
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Standalone K2 implementation of the IDEA search utilities used by copied refactoring engines.
 *
 * The active standalone session supplies build-scoped source PSI, while
 * [StandaloneInheritorSearch] exposes whole-session override discovery. This deliberately covers
 * Kotlin source declarations only; IDEA index-backed Java and library searches remain unsupported.
 */
object KotlinSearchUsagesSupport {
    @JvmStatic
    fun getInstance(project: Project): KotlinSearchUsagesSupport = this

    object SearchUtils {
        /** Returns the direct K2-resolved super declarations of [element] in the active session. */
        fun findSuperMethodsNoWrapping(element: PsiElement): List<PsiElement> {
            val declaration = element as? KtNamedDeclaration ?: return emptyList()
            return runCatching {
                analyze(declaration) {
                    val symbol = declaration.symbol as? KaCallableSymbol
                    if (symbol == null) {
                        emptyList<PsiElement>()
                    } else {
                        val superMethods: List<PsiElement> = symbol.allOverriddenSymbols
                            .mapNotNull { it.psi }
                            .distinct()
                            .toList()
                        superMethods
                    }
                }
            }.getOrElse { emptyList() }
        }

        /** IDEA compatibility predicate: Kotlin declarations are potentially overridable. */
        fun KtDeclaration.isOverridable(): Boolean = true
    }

    /** Returns whether [candidate] and [declaration] belong to the same K2 override chain. */
    fun isCallableOverride(declaration: KtDeclaration, candidate: PsiNamedElement): Boolean {
        val namedCandidate = candidate as? KtNamedDeclaration ?: return false
        if (declaration !is KtNamedDeclaration) return false
        if (declaration == namedCandidate) return true
        if (StandaloneInheritorSearch.searchOverriderElements(declaration, true).any { it == namedCandidate }) return true
        return StandaloneInheritorSearch.searchOverriderElements(namedCandidate, true).any { it == declaration }
    }
}
