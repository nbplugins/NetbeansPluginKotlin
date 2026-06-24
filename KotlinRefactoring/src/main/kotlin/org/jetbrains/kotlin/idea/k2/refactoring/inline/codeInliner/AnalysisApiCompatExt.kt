// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(kotlin.ExperimentalContextParameters::class)
package org.jetbrains.kotlin.idea.k2.refactoring.inline.codeInliner

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType

// ── KaType primitive predicates (isXxx → isXxxType in 2.3.x) ─────────────────
context(KaSession) val KaType.isInt: Boolean get() = isIntType
context(KaSession) val KaType.isLong: Boolean get() = isLongType
context(KaSession) val KaType.isShort: Boolean get() = isShortType
context(KaSession) val KaType.isChar: Boolean get() = isCharType
context(KaSession) val KaType.isBoolean: Boolean get() = isBooleanType
context(KaSession) val KaType.isByte: Boolean get() = isByteType
context(KaSession) val KaType.isDouble: Boolean get() = isDoubleType
context(KaSession) val KaType.isFloat: Boolean get() = isFloatType
context(KaSession) val KaType.isUnit: Boolean get() = isUnitType
context(KaSession) val KaType.isString: Boolean get() = isStringType

/** Backward-compat: was [KaType.isEqualTo] in 2.0.x, now [semanticallyEquals]. */
context(KaSession)
fun KaType.isEqualTo(other: KaType): Boolean = semanticallyEquals(other)

/** Backward-compat: was [KaScope.getAllSymbols] in 2.0.x, now [declarations]. */
context(KaSession)
fun KaScope.getAllSymbols(): Sequence<KaDeclarationSymbol> = declarations
