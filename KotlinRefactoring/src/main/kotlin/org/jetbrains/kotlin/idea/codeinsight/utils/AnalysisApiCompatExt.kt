// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(kotlin.ExperimentalContextParameters::class)
package org.jetbrains.kotlin.idea.codeinsight.utils

import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.name.Name

/** Backward-compat alias: [KaNamedClassOrObjectSymbol] was renamed to [KaNamedClassSymbol] in 2.3.x. */
typealias KaNamedClassOrObjectSymbol = KaNamedClassSymbol

/**
 * Backward-compat shims for files in this package.
 * Same-package location means no import needed by callers in this package.
 */

// ── KaType primitive predicates (isXxx → isXxxType in 2.3.x) ─────────────────────────────
context(KaSession) val KaType.isUnit: Boolean get() = isUnitType
context(KaSession) val KaType.isString: Boolean get() = isStringType
context(KaSession) val KaType.isBoolean: Boolean get() = isBooleanType
context(KaSession) val KaType.isByte: Boolean get() = isByteType
context(KaSession) val KaType.isChar: Boolean get() = isCharType
context(KaSession) val KaType.isAny: Boolean get() = isAnyType
context(KaSession) val KaType.isNothing: Boolean get() = isNothingType
context(KaSession) val KaType.isDouble: Boolean get() = isDoubleType
context(KaSession) val KaType.isFloat: Boolean get() = isFloatType
context(KaSession) val KaType.isInt: Boolean get() = isIntType
context(KaSession) val KaType.isLong: Boolean get() = isLongType
context(KaSession) val KaType.isShort: Boolean get() = isShortType

/** Stub: reformat is a no-op in standalone NB mode. */
fun PsiFile.reformat(startOffset: Int, endOffset: Int) {}

/** Backward-compat: was [KtDeclaration.getSymbolOfType] in 2.0.x, now [symbol] + cast. */
@Suppress("UNCHECKED_CAST")
context(KaSession)
inline fun <reified T : KaDeclarationSymbol> KtDeclaration.getSymbolOfType(): T = symbol as T

/** Backward-compat: was [KtClassOrObject.getClassOrObjectSymbol] in 2.0.x, now [classSymbol]. */
context(KaSession)
fun KtClassOrObject.getClassOrObjectSymbol(): KaClassSymbol? = classSymbol

/** Backward-compat: was [KtFunction.getFunctionLikeSymbol] in 2.0.x, now [symbol]. */
context(KaSession)
fun KtFunction.getFunctionLikeSymbol(): KaFunctionSymbol = symbol as KaFunctionSymbol

/** Backward-compat: was [KtParameter.getParameterSymbol] in 2.0.x, now [symbol]. */
context(KaSession)
fun KtParameter.getParameterSymbol(): KaVariableSymbol = symbol as KaVariableSymbol

/** Backward-compat: was [KaNamedClassSymbol.companionObject] in 2.0.x, now [companionObject]. */
context(KaSession)
val KaNamedClassSymbol.companionObject: KaNamedClassSymbol?
    get() = staticDeclaredMemberScope.classifiers(Name.identifier("Companion"))
        .filterIsInstance<KaNamedClassSymbol>()
        .firstOrNull { it.classKind == KaClassKind.COMPANION_OBJECT }
