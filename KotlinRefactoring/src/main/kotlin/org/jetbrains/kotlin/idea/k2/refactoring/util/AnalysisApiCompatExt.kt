// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(kotlin.ExperimentalContextParameters::class, org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class)
package org.jetbrains.kotlin.idea.k2.refactoring.util

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression

/** Backward-compat: [KaReceiverParameterSymbol.type] was [type] in 2.0.x, now [returnType]. */
val KaReceiverParameterSymbol.type: KaType get() = returnType

/** Backward-compat alias: renamed to [KaNamedClassSymbol] in 2.3.x. */
typealias KaNamedClassOrObjectSymbol = KaNamedClassSymbol

// ── KaType primitive predicates (isXxx → isXxxType in 2.3.x) ─────────────────
context(KaSession) val KaType.isNothing: Boolean get() = isNothingType
context(KaSession) val KaType.isUnit: Boolean get() = isUnitType
context(KaSession) val KaType.isString: Boolean get() = isStringType

/** Backward-compat: was [KaType.isEqualTo] in 2.0.x, now [semanticallyEquals]. */
context(KaSession)
fun KaType.isEqualTo(other: KaType): Boolean = semanticallyEquals(other)

/** Backward-compat: was [KaType.isSubTypeOf] in 2.0.x, now [isSubtypeOf]. */
context(KaSession)
fun KaType.isSubTypeOf(supertype: KaType): Boolean = isSubtypeOf(supertype)

/** Backward-compat: was [KtCallableReferenceExpression.getReceiverKtType], now [receiverType]. */
context(KaSession)
fun KtCallableReferenceExpression.getReceiverKtType(): KaType? = receiverType

/** Backward-compat: was [KaNamedClassSymbol.isInner], now [isInner] (same name). */
context(KaSession)
val KaNamedClassSymbol.isInnerCompat: Boolean get() = isInner
