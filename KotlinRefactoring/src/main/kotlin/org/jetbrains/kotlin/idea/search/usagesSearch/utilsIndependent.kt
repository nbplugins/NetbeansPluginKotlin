// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.search.usagesSearch

import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageSearchService
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * In IDEA, searches for `this(...)`/`super(...)` constructor-delegation calls that target this
 * declaration's constructor. Used by the ported Change Signature engine (E9.8) to update
 * delegation-call usages when a constructor's signature changes.
 *
 * Standalone reference search has no [com.intellij.psi.search.searches.ReferencesSearch] index to
 * drive from (same limitation as every other IDE-index-backed search in this plugin); delegates to
 * [KotlinChangeSignatureUsageSearchService.findConstructorDelegationCallers], which reuses the same
 * whole-project scan-and-resolve approach already used for plain reference search — a no-op if no
 * service is registered (e.g. outside a running plugin container, as in some unit tests).
 */
fun KtDeclaration.processDelegationCallConstructorUsages(
    scope: SearchScope,
    process: (PsiElement) -> Boolean
) {
    val service = KotlinChangeSignatureUsageSearchService.getInstance() ?: return
    for (callElement in service.findConstructorDelegationCallers(this)) {
        if (!process(callElement)) break
    }
}
