// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring.rename

import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Stub: IDEA's real [KotlinRenameRefactoringSupport] is an application service with many members
 * used across Rename/Move/Change Signature. The ported Change Signature engine (E9.8) only calls
 * [dropOverrideKeywordIfNecessary], so only that narrow slice is provided here (same "narrow stub,
 * not full port" approach already used for [org.jetbrains.kotlin.idea.search.KotlinSearchUsagesSupport]).
 *
 * Dropping a stale `override` keyword requires knowing whether a declaration still overrides
 * anything after its signature changed, which needs a whole-project inheritor index this plugin
 * doesn't have standalone (same limitation already accepted elsewhere, e.g.
 * [org.jetbrains.kotlin.idea.search.declarationsSearch.forEachOverridingElement]). To avoid ever
 * silently removing a keyword the user still needs, this is conservatively a no-op.
 */
object KotlinRenameRefactoringSupport {
    fun getInstance(): KotlinRenameRefactoringSupport = this

    fun dropOverrideKeywordIfNecessary(element: KtNamedDeclaration) {
        // No-op: see class KDoc.
    }
}
