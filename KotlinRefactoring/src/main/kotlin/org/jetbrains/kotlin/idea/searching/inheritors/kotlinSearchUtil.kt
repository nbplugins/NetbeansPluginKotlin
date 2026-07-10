// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.searching.inheritors

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallableDeclaration

/**
 * Stub: finds all overriding declarations of a callable.
 *
 * Used by the ported Change Signature engine (E9.8) to propagate a signature change to overrides.
 * There is no whole-project inheritor index in standalone mode (same limitation already accepted
 * for Move Declaration's hierarchy-based conflict checks and [org.jetbrains.kotlin.idea.search.declarationsSearch.forEachOverridingElement]),
 * so this always returns an empty sequence.
 */
fun KtCallableDeclaration.findAllOverridings(): Sequence<PsiElement> = emptySequence()
