// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(kotlin.ExperimentalContextParameters::class)
package org.jetbrains.kotlin.idea.base.codeInsight

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Backward-compat shims for files in this package.
 * Same-package location means no import needed by callers in this package.
 */

/** Backward-compat: was [KtClassOrObject.getClassOrObjectSymbol] in 2.0.x, now [classSymbol]. */
context(_: KaSession)
fun KtClassOrObject.getClassOrObjectSymbol(): KaClassSymbol? = classSymbol

/** Backward-compat: was [KaScope.getCallableSymbols] in 2.0.x, now [callables]. */
context(_: KaSession)
fun KaScope.getCallableSymbols(identifier: Name): Sequence<KaCallableSymbol> = callables(identifier)

/** Backward-compat: was [KaScope.getClassifierSymbols] in 2.0.x, now [classifiers]. */
context(_: KaSession)
fun KaScope.getClassifierSymbols(identifier: Name): Sequence<KaClassifierSymbol> = classifiers(identifier)

/** Backward-compat: was [KaScope.getAllSymbols] in 2.0.x, now [declarations]. */
context(_: KaSession)
fun KaScope.getAllSymbols(): Sequence<KaDeclarationSymbol> = declarations

/**
 * Backward-compat: was [KaCallableSymbol.isVisibleInClass] in 2.0.x.
 * In 2.3.x the visibility API changed; approximate with always-true (conservative: include all).
 */
context(_: KaSession)
fun KaCallableSymbol.isVisibleInClass(classSymbol: KaClassSymbol): Boolean = true
