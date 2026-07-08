// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search

import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Stub: IDEA's real [ExpectActualUtils] resolves `expect`/`actual` counterparts across Kotlin
 * Multiplatform source sets via [org.jetbrains.kotlin.idea.base.projectStructure] module indices.
 *
 * This plugin has no multiplatform project model — every NetBeans project gets one isolated K2
 * analysis session (confirmed via dedicated research for Move Declaration/E9.7, see
 * [org.jetbrains.kotlin.idea.k2.refactoring.move.K2MoveDeclarationsRefactoringProcessor] and the
 * `SingletonModule`/`ModuleUtilCore` stubs). So a declaration is always its own (and only) actual
 * declaration: [liftToExpect] never finds an `expect` counterpart, and [withExpectedActuals] always
 * returns just the declaration itself.
 */
object ExpectActualUtils {
    fun liftToExpect(declaration: KtDeclaration): KtDeclaration? = null

    fun withExpectedActuals(declaration: KtNamedDeclaration): List<KtNamedDeclaration> = listOf(declaration)
}
