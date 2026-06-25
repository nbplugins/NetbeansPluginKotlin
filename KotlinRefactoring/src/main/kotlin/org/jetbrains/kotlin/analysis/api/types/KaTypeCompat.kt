// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(kotlin.ExperimentalContextParameters::class)
package org.jetbrains.kotlin.analysis.api.types

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.*

/**
 * Backward-compat shims for [KaType] property renames in analysis-api 2.3.x.
 * In 2.0.x: `isBoolean`, `isString`, etc. → in 2.3.x: `isBooleanType`, `isStringType`, etc.
 * Files that import `org.jetbrains.kotlin.analysis.api.types.*` pick these up automatically.
 */

context(_: KaSession)
val KaType.isUnit: Boolean get() = isUnitType

context(_: KaSession)
val KaType.isString: Boolean get() = isStringType

context(_: KaSession)
val KaType.isBoolean: Boolean get() = isBooleanType

context(_: KaSession)
val KaType.isByte: Boolean get() = isByteType

context(_: KaSession)
val KaType.isChar: Boolean get() = isCharType

context(_: KaSession)
val KaType.isCharSequence: Boolean get() = isCharSequenceType

context(_: KaSession)
val KaType.isAny: Boolean get() = isAnyType

context(_: KaSession)
val KaType.isNothing: Boolean get() = isNothingType

context(_: KaSession)
val KaType.isDouble: Boolean get() = isDoubleType

context(_: KaSession)
val KaType.isFloat: Boolean get() = isFloatType

context(_: KaSession)
val KaType.isInt: Boolean get() = isIntType

context(_: KaSession)
val KaType.isLong: Boolean get() = isLongType

context(_: KaSession)
val KaType.isShort: Boolean get() = isShortType

context(_: KaSession)
val KaType.isUByte: Boolean get() = isUByteType

context(_: KaSession)
val KaType.isUInt: Boolean get() = isUIntType

context(_: KaSession)
val KaType.isULong: Boolean get() = isULongType

context(_: KaSession)
val KaType.isUShort: Boolean get() = isUShortType

/** Backward-compat: was [KaType.isEqualTo] in 2.0.x, now [semanticallyEquals]. */
context(_: KaSession)
fun KaType.isEqualTo(other: KaType): Boolean = semanticallyEquals(other)

/** Backward-compat: was [KaType.isSubTypeOf] in 2.0.x, now [isSubtypeOf]. */
context(_: KaSession)
fun KaType.isSubTypeOf(supertype: KaType): Boolean = isSubtypeOf(supertype)

/** Backward-compat: was [KaType.isNotSubTypeOf] in 2.0.x, now [!isSubtypeOf]. */
context(_: KaSession)
fun KaType.isNotSubTypeOf(supertype: KaType): Boolean = !isSubtypeOf(supertype)

/** Backward-compat: collects all supertypes via BFS on [KaType.directSupertypes]. */
context(_: KaSession)
fun KaType.getAllSuperTypes(includeAny: Boolean = false): List<KaType> {
    val result = mutableListOf<KaType>()
    val queue = ArrayDeque(directSupertypes(shouldApproximate = false).toList())
    val seen = mutableSetOf<KaType>()
    while (queue.isNotEmpty()) {
        val type = queue.removeFirst()
        if (!seen.add(type)) continue
        if (!includeAny && type.isAny) continue
        result.add(type)
        queue.addAll(type.directSupertypes(shouldApproximate = false))
    }
    return result
}
