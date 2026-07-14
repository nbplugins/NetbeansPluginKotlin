// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.searching.inheritors

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageSearchService
import org.jetbrains.kotlin.psi.KtCallableDeclaration

/**
 * Finds all overriding declarations of a callable, project-wide.
 *
 * Used by the ported Change Signature engine (E9.8) to propagate a signature change to overrides
 * (`KotlinOverrideUsageInfo`). There is no whole-project inheritor index in standalone mode (same
 * limitation already accepted for Move Declaration's hierarchy-based conflict checks and
 * [org.jetbrains.kotlin.idea.search.declarationsSearch.forEachOverridingElement]), so this delegates
 * to [KotlinChangeSignatureUsageSearchService.findOverridings], which reuses the whole-project
 * scan-and-resolve approach already used for plain reference search — returns an empty sequence if
 * no service is registered (e.g. outside a running plugin container, as in some unit tests).
 */
fun KtCallableDeclaration.findAllOverridings(): Sequence<PsiElement> =
    (KotlinChangeSignatureUsageSearchService.getInstance()?.findOverridings(this) ?: emptyList()).asSequence()
