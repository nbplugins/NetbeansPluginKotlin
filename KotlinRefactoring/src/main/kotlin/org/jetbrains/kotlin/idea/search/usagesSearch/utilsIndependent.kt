// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search.usagesSearch

import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Stub: in IDEA, searches for `this(...)`/`super(...)` constructor-delegation calls that target
 * this declaration's constructor. Used by the ported Change Signature engine (E9.8) to update
 * delegation-call usages when a constructor's signature changes.
 *
 * Standalone reference search has no [com.intellij.psi.search.searches.ReferencesSearch] index to
 * drive from (same limitation as every other IDE-index-backed search in this plugin); constructor
 * delegation usages are instead surfaced by
 * [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageSearchService]'s
 * whole-project PSI scan (M3 milestone), so this call is a no-op here.
 */
fun KtDeclaration.processDelegationCallConstructorUsages(
    scope: SearchScope,
    process: (PsiElement) -> Boolean
) {
    // No-op: constructor delegation call usages are surfaced by KotlinChangeSignatureUsageSearchService instead.
}
