// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(kotlin.ExperimentalContextParameters::class)
package org.jetbrains.kotlin.idea.k2.refactoring.introduce

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.*
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol

/** Backward-compat: was [KtTypeParameter.getTypeParameterSymbol], now [symbol]. */
context(_: KaSession)
fun KtTypeParameter.getTypeParameterSymbol(): KaTypeParameterSymbol = symbol

// ── KaBuiltinTypes rename (STRING → string in 2.3.x) ─────────────────────────
context(_: KaSession)
val org.jetbrains.kotlin.analysis.api.components.KaBuiltinTypes.STRING: KaType
    get() = string

// ── KaType predicates (isXxx → isXxxType in 2.3.x) ──────────────────────────
context(_: KaSession) val KaType.isString: Boolean get() = isStringType

/** Backward-compat: was [KaType.isEqualTo] in 2.0.x, now [semanticallyEquals]. */
context(_: KaSession)
fun KaType.isEqualTo(other: KaType): Boolean = semanticallyEquals(other)

/** Backward-compat: was [KaType.isSubTypeOf] in 2.0.x, now [isSubtypeOf]. */
context(_: KaSession)
fun KaType.isSubTypeOf(supertype: KaType): Boolean = isSubtypeOf(supertype)

/** Backward-compat: was [KtFunctionLiteral.getAnonymousFunctionSymbol], now [symbol]. */
context(_: KaSession)
fun KtFunctionLiteral.getAnonymousFunctionSymbol(): KaAnonymousFunctionSymbol = symbol

/** Backward-compat: was [KtNamedFunction.getAnonymousFunctionSymbol], now [symbol]. */
context(_: KaSession)
fun KtNamedFunction.getAnonymousFunctionSymbol(): KaFunctionSymbol = symbol as KaFunctionSymbol

/** Backward-compat: was [KtFunction.getFunctionLikeSymbol], now [symbol]. */
context(_: KaSession)
fun KtFunction.getFunctionLikeSymbol(): KaFunctionSymbol = symbol as KaFunctionSymbol

/** Backward-compat: was [KtCallableDeclaration.getCallableSymbol], now [symbol] cast. */
@Suppress("UNCHECKED_CAST")
context(_: KaSession)
fun KtCallableDeclaration.getCallableSymbol(): KaCallableSymbol = symbol as KaCallableSymbol

/** Backward-compat: was [KtDeclaration.getSymbolOfType], now [symbol] + cast. */
@Suppress("UNCHECKED_CAST")
context(_: KaSession)
inline fun <reified T : KaDeclarationSymbol> KtDeclaration.getSymbolOfType(): T = symbol as T
