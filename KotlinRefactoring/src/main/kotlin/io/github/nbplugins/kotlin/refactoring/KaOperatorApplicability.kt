/*******************************************************************************
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package io.github.nbplugins.kotlin.refactoring

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.allSupertypes
import org.jetbrains.kotlin.analysis.api.components.canBeCalledAsExtensionOn
import org.jetbrains.kotlin.analysis.api.components.createSubstitutor
import org.jetbrains.kotlin.analysis.api.components.expandedSymbol
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.components.memberScope
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaCapturedType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtArrayAccessExpression

/**
 * Returns this callable's return type with its type arguments inferred from [receiverType].
 *
 * A generic extension declares its return type in terms of its own type parameters
 * (`operator fun <T> Delegate<T>.provideDelegate(...): Provided<T>`), while the compiler
 * instantiates them from the receiver. The delegated property's accessors are selected on
 * `Provided<Int>` for a `Delegate<Int>` delegate expression, so the declared `Provided<T>` cannot
 * be used directly. Unifying the declared receiver with the actual one yields the mapping.
 *
 * A callable without a receiver keeps its declared return type; unifyable structures also fall
 * back to it, because guessing an instantiation would select the wrong operator.
 *
 * @param receiverType actual type the callable is invoked on
 * @return the return type with the inferred type arguments substituted
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KaCallableSymbol.returnTypeInferredFrom(receiverType: KaType): KaType {
    val declaredReceiverType = receiverParameter?.returnType ?: return returnType
    return runCatching {
        val mappings = linkedMapOf<KaTypeParameterSymbol, KaType>()
        if (!collectTypeMappings(declaredReceiverType, receiverType, mappings)) return@runCatching returnType
        if (mappings.isEmpty()) return@runCatching returnType
        createSubstitutor(mappings).substitute(returnType)
    }.getOrDefault(returnType)
}

/**
 * Collects the type-parameter instantiations that make [actual] match [declared].
 *
 * Only type parameters and class types contribute mappings; any other declared structure is
 * accepted without constraining the inference, since the receiver-applicability check has already
 * proved that the call is possible.
 *
 * @param declared type spelled by the declaration
 * @param actual type supplied at the call site
 * @param mappings accumulator of type-parameter instantiations
 * @return `false` when the same type parameter would need two different instantiations
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun collectTypeMappings(
    declared: KaType,
    actual: KaType,
    mappings: MutableMap<KaTypeParameterSymbol, KaType>,
): Boolean {
    val declaredType = unwrapType(declared)
    val actualType = unwrapType(actual)
    return when (declaredType) {
        is KaTypeParameterType -> {
            val previous = mappings[declaredType.symbol]
            if (previous == null) {
                mappings[declaredType.symbol] = actualType
                true
            } else {
                previous.semanticallyEquals(actualType)
            }
        }
        is KaClassType -> {
            val actualClassType = actualType as? KaClassType ?: return true
            val matchingType = if (declaredType.classId == actualClassType.classId) {
                actualClassType
            } else {
                // A subtype supplies the type arguments indirectly: `IntDelegate : Delegate<Int>()`
                // is instantiated as `Provided<Int>` by a provider declared on `Delegate<T>`, so the
                // declared supertype has to be unified instead of giving up on the class mismatch.
                actualClassType.allSupertypes
                    .mapNotNull { supertype -> supertype as? KaClassType }
                    .firstOrNull { supertype -> supertype.classId == declaredType.classId }
                    ?: return true
            }
            val declaredArguments = declaredType.typeArguments
            val actualArguments = matchingType.typeArguments
            if (declaredArguments.size != actualArguments.size) return true
            declaredArguments.indices.all { index ->
                val declaredArgument = declaredArguments[index].type ?: return@all true
                val actualArgument = actualArguments[index].type ?: return@all true
                collectTypeMappings(declaredArgument, actualArgument, mappings)
            }
        }
        else -> true
    }
}

/**
 * Removes the wrappers that hide a type's structure from inference.
 *
 * @param type type to inspect
 * @return the same type without flexibility, capture, or definite-non-null wrappers
 */
private fun unwrapType(type: KaType): KaType = when (type) {
    is KaDefinitelyNotNullType -> unwrapType(type.original)
    is KaCapturedType -> type.projection.type?.let(::unwrapType) ?: type
    is KaFlexibleType -> unwrapType(type.upperBound)
    else -> type
}

/**
 * Returns the argument types a compound indexed write passes to `set`.
 *
 * The write passes its indices and then the value produced by the getter and the compound
 * operation. The getter's return type stands in for that value: for the integer and boxed types
 * the operators produce the same type, and the member-setter applicability check only needs
 * enough precision to reject a signature whose index or value type cannot fit.
 *
 * @return index types followed by the getter's return type, or `null` when unresolved
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KtArrayAccessExpression.setterArgumentTypes(): List<KaType?>? {
    val indices = indexExpressions.map { index ->
        runCatching { index.expressionType }.getOrNull() ?: return null
    }
    val getter = references.filterIsInstance<KtReference>()
        .flatMap { reference -> runCatching { reference.resolveToSymbols() }.getOrNull().orEmpty() }
        .filterIsInstance<KaCallableSymbol>()
        .firstOrNull { symbol -> (symbol as? KaNamedSymbol)?.name?.asString() == "get" }
        ?: return null
    return indices + runCatching { getter.returnType }.getOrNull()
}

/**
 * Tells whether this callable accepts the arguments of an indexed write.
 *
 * An indexed write passes the indices and then the assigned value. A parameter whose type is a
 * type parameter or cannot be compared is accepted, because the alternative — treating it as
 * inapplicable — would import an extension the member already shadows.
 *
 * @param arguments argument types in call order; `null` entries are unresolved expressions
 * @return `true` when the callable can be selected for those arguments
 */
context(_: KaSession)
internal fun KaCallableSymbol.acceptsIndexedSetterArguments(arguments: List<KaType?>): Boolean = runCatching {
    val function = this as? KaFunctionSymbol ?: return@runCatching false
    val parameters = function.valueParameters
    if (parameters.isEmpty()) return@runCatching false
    val lastParameter = parameters.last()
    val fixedCount = if (lastParameter.isVararg) parameters.size - 1 else parameters.size
    if (arguments.size < fixedCount) return@runCatching false
    if (!lastParameter.isVararg && arguments.size != parameters.size) return@runCatching false
    arguments.withIndex().all { (index, argument) ->
        val parameter = parameters.getOrNull(index) ?: lastParameter
        argument == null || acceptsArgument(parameter.returnType, argument)
    }
}.getOrDefault(false)

/**
 * Tells whether a value parameter of [parameterType] takes [argument].
 *
 * @param parameterType declared parameter type
 * @param argument actual argument type
 * @return `true` when assignability holds or cannot be decided
 */
context(_: KaSession)
private fun acceptsArgument(parameterType: KaType, argument: KaType): Boolean = runCatching {
    parameterType is KaTypeParameterType || argument.isSubtypeOf(parameterType)
}.getOrDefault(true)

/**
 * Selects the extension the compiler binds for a convention call.
 *
 * Every candidate is visible through the same package scopes, so overload resolution ranks them
 * by receiver specificity: `SubBox.set` wins over `Box.set` for a `SubBox` receiver. Candidates
 * that cannot take [arguments] are dropped first, which separates overloads declared on the same
 * receiver type.
 *
 * @param candidates extensions visible under the same simple name
 * @param receiverType type of the value the operator is invoked on
 * @param arguments argument types of the call, or `null` when they cannot be resolved
 * @return the selected extension, or `null` when none applies
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun selectExtension(
    candidates: List<KaCallableSymbol>,
    receiverType: KaType,
    arguments: List<KaType?>? = null,
): KaCallableSymbol? {
    val applicable = candidates.filter { candidate ->
        runCatching { candidate.canBeCalledAsExtensionOn(receiverType) }.getOrDefault(false) &&
            (arguments == null || candidate.acceptsIndexedSetterArguments(arguments))
    }
    return applicable.firstOrNull { candidate ->
        applicable.none { other -> other !== candidate && other.receiverIsMoreSpecificThan(candidate) }
    } ?: applicable.firstOrNull()
}

/**
 * Tells whether this callable's receiver is a proper subtype of [other]'s.
 *
 * The declared receiver types decide which extension overload wins when both are visible through
 * the same scope. Equal types are not "more specific", so a tie keeps the declaration order.
 *
 * @param other competing callable
 * @return `true` when this receiver fits [other]'s receiver but not the other way round
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaCallableSymbol.receiverIsMoreSpecificThan(other: KaCallableSymbol): Boolean = runCatching {
    val receiver = receiverParameter?.returnType ?: return@runCatching false
    val otherReceiver = other.receiverParameter?.returnType ?: return@runCatching false
    receiver.isSubtypeOf(otherReceiver) && !otherReceiver.isSubtypeOf(receiver)
}.getOrDefault(false)

/**
 * Tells whether this type declares a member `set` that accepts the indexed-write arguments.
 *
 * A member setter always wins over an extension, but only when its signature actually fits: a
 * `set(String, Int)` member does not shadow `box[0]++`, which needs `set(Int, Int)`. An unknown
 * argument list keeps the member as applicable, preserving the previous conservative behavior.
 *
 * @param arguments argument types of the write, or `null` when they cannot be resolved
 * @return `true` when a member setter of this type can be selected
 */
context(_: KaSession)
internal fun KaType.hasApplicableMemberIndexedSetter(arguments: List<KaType?>?): Boolean = runCatching {
    if (arguments == null) return@runCatching true
    expandedSymbol?.memberScope?.declarations
        .orEmpty()
        .filterIsInstance<KaFunctionSymbol>()
        .any { symbol ->
            (symbol as? KaNamedSymbol)?.name?.asString() == "set" &&
                symbol.acceptsIndexedSetterArguments(arguments)
        }
}.getOrDefault(true)
