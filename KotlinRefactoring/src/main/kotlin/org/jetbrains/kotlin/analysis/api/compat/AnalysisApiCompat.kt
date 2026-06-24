// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("UNCHECKED_CAST")
@file:OptIn(
    kotlin.ExperimentalContextParameters::class,
    org.jetbrains.kotlin.analysis.api.KaContextParameterApi::class,
    org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction::class,
    org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
)
package org.jetbrains.kotlin.analysis.api.compat

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaSubtypingErrorTypePolicy
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*

// ── Type aliases: renamed in analysis-api 2.0.x → 2.3.x ───────────────────────────────────
/** Backward-compat alias: renamed to [KaNamedClassSymbol] in analysis-api 2.3.x. */
typealias KaNamedClassOrObjectSymbol = KaNamedClassSymbol

/** Backward-compat alias: renamed to [KaDeclarationContainerSymbol] in analysis-api 2.3.x. */
typealias KaSymbolWithMembers = KaDeclarationContainerSymbol

// ── KaType primitive predicates (isXxx → isXxxType in 2.3.x) ─────────────────────────────

context(KaSession)
val KaType.isUnit: Boolean get() = isUnitType

context(KaSession)
val KaType.isString: Boolean get() = isStringType

context(KaSession)
val KaType.isBoolean: Boolean get() = isBooleanType

context(KaSession)
val KaType.isByte: Boolean get() = isByteType

context(KaSession)
val KaType.isChar: Boolean get() = isCharType

context(KaSession)
val KaType.isCharSequence: Boolean get() = isCharSequenceType

context(KaSession)
val KaType.isAny: Boolean get() = isAnyType

context(KaSession)
val KaType.isNothing: Boolean get() = isNothingType

context(KaSession)
val KaType.isDouble: Boolean get() = isDoubleType

context(KaSession)
val KaType.isFloat: Boolean get() = isFloatType

context(KaSession)
val KaType.isInt: Boolean get() = isIntType

context(KaSession)
val KaType.isLong: Boolean get() = isLongType

context(KaSession)
val KaType.isShort: Boolean get() = isShortType

context(KaSession)
val KaType.isUByte: Boolean get() = isUByteType

context(KaSession)
val KaType.isUInt: Boolean get() = isUIntType

context(KaSession)
val KaType.isULong: Boolean get() = isULongType

context(KaSession)
val KaType.isUShort: Boolean get() = isUShortType

// ── KaType relation (renamed/refactored in 2.3.x) ────────────────────────────────────────

/** Backward-compat: was [KaType.isEqualTo] in old API, now [semanticallyEquals]. */
context(KaSession)
fun KaType.isEqualTo(other: KaType): Boolean = semanticallyEquals(other)

/** Backward-compat: was [KaType.isSubTypeOf] in old API, now [isSubtypeOf]. */
context(KaSession)
fun KaType.isSubTypeOf(supertype: KaType): Boolean =
    isSubtypeOf(supertype, KaSubtypingErrorTypePolicy.LENIENT)

/** BFS traversal of all super-types (was getAllSuperTypes() in old API). */
context(KaSession)
fun KaType.getAllSuperTypes(includeAny: Boolean = false): List<KaType> {
    val result = mutableListOf<KaType>()
    val queue = ArrayDeque<KaType>()
    val seen = mutableSetOf<KaType>()
    queue.add(this)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val symbol = current.expandedSymbol ?: continue
        for (superType in symbol.superTypes) {
            if (seen.add(superType)) {
                result.add(superType)
                queue.add(superType)
            }
        }
    }
    return if (includeAny) result else result.filter { !it.isAnyType }
}

// ── Scope APIs (renamed in 2.3.x) ────────────────────────────────────────────────────────

/** Backward-compat: was [KaScope.getCallableSymbols] in old API, now [callables]. */
context(KaSession)
fun KaScope.getCallableSymbols(identifier: Name): Sequence<KaCallableSymbol> = callables(identifier)

/** Backward-compat: was [KaScope.getClassifierSymbols] in old API, now [classifiers]. */
context(KaSession)
fun KaScope.getClassifierSymbols(identifier: Name): Sequence<KaClassifierSymbol> = classifiers(identifier)

/** Backward-compat: was [KaScope.getAllSymbols] in old API, now the [declarations] property. */
context(KaSession)
fun KaScope.getAllSymbols(): Sequence<KaDeclarationSymbol> = declarations

// ── KtElement → KaSymbol (renamed/refactored in 2.3.x) ───────────────────────────────────

/** Backward-compat: was [KtClassOrObject.getClassOrObjectSymbol], now [classSymbol]. */
context(KaSession)
fun KtClassOrObject.getClassOrObjectSymbol(): KaClassSymbol? = classSymbol

/** Backward-compat: was [KtDeclaration.getSymbolOfType], now [symbol] + cast. */
context(KaSession)
inline fun <reified T : KaDeclarationSymbol> KtDeclaration.getSymbolOfType(): T = symbol as T

/** Backward-compat: was [KtFunctionLiteral.getAnonymousFunctionSymbol], now [symbol]. */
context(KaSession)
fun KtFunctionLiteral.getAnonymousFunctionSymbol(): KaAnonymousFunctionSymbol = symbol

/** Backward-compat for use on [KtNamedFunction] (returns anonymous symbol for literals). */
context(KaSession)
fun KtNamedFunction.getAnonymousFunctionSymbol(): KaFunctionSymbol = symbol as KaFunctionSymbol

/** Backward-compat: was [KtFunction.getFunctionLikeSymbol], now [symbol]. */
context(KaSession)
fun KtFunction.getFunctionLikeSymbol(): KaFunctionSymbol = symbol as KaFunctionSymbol

/** Backward-compat: was [KtParameter.getParameterSymbol], now [symbol]. */
context(KaSession)
fun KtParameter.getParameterSymbol(): KaVariableSymbol = symbol

/** Backward-compat: was [KtTypeParameter.getTypeParameterSymbol], now [symbol]. */
context(KaSession)
fun KtTypeParameter.getTypeParameterSymbol(): KaTypeParameterSymbol = symbol

/** Backward-compat: was [KtCallableDeclaration.getCallableSymbol], now [symbol] cast. */
context(KaSession)
fun KtCallableDeclaration.getCallableSymbol(): KaCallableSymbol = symbol as KaCallableSymbol

// ── KaCallableSymbol extensions (renamed/refactored in 2.3.x) ─────────────────────────────

/**
 * Backward-compat: the class containing the original overridden declaration.
 * In 2.3.x, approximated via [fakeOverrideOriginal].containingSymbol.
 */
context(KaSession)
val KaCallableSymbol.originalContainingClassForOverride: KaNamedClassSymbol?
    get() = fakeOverrideOriginal.containingSymbol as? KaNamedClassSymbol

// ── KtCallableReferenceExpression receiver type ───────────────────────────────────────────

/** Backward-compat: was getReceiverKtType(), now [receiverType]. */
context(KaSession)
fun KtCallableReferenceExpression.getReceiverKtType(): KaType? = receiverType
