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
import org.jetbrains.kotlin.analysis.api.components.buildClassType
import org.jetbrains.kotlin.analysis.api.components.buildTypeParameterType
import org.jetbrains.kotlin.analysis.api.components.builtinTypes
import org.jetbrains.kotlin.analysis.api.components.canBeCalledAsExtensionOn
import org.jetbrains.kotlin.analysis.api.components.createSubstitutor
import org.jetbrains.kotlin.analysis.api.components.defaultType
import org.jetbrains.kotlin.analysis.api.components.expandedSymbol
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.components.memberScope
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.components.scopeContext
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.components.type
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters
import org.jetbrains.kotlin.analysis.api.types.KaCapturedType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaIntersectionType
import org.jetbrains.kotlin.analysis.api.types.KaSubstitutor
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeNullability
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Returns this callable's return type with its type arguments inferred from its call.
 *
 * A generic extension declares its return type in terms of its own type parameters
 * (`operator fun <T> Delegate<T>.provideDelegate(...): Provided<T>`), while the compiler
 * instantiates them from the receiver and from the implicit call's arguments. The delegated
 * property's accessors are selected on `Provided<Int>` for a `Delegate<Int>` delegate expression,
 * and on `Provided<Host>` for a provider declared as
 * `operator fun <T> Delegate.provideDelegate(thisRef: T, ...)`, whose `T` only the `thisRef`
 * argument determines. Unifying the declared receiver and parameters with the actual ones yields
 * the mapping.
 *
 * A callable whose structures cannot be unified keeps its declared return type, because guessing
 * an instantiation would select the wrong operator. A member provider has no extension receiver,
 * so its class type arguments come from the instantiated scope of the receiver type:
 * `Delegate<T>.provideDelegate` selected on `Delegate<Int>` returns `Provided<Int>`.
 *
 * @param receiverType actual type the callable is invoked on
 * @param arguments argument types of the implicit call, or `null` when they cannot be resolved
 * @return the return type with the inferred type arguments substituted
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KaCallableSymbol.returnTypeInferredFrom(
    receiverType: KaType,
    arguments: List<KaType?>? = null,
): KaType = runCatching {
    val declaredReturnType = returnType
    val mappings = linkedMapOf<KaTypeParameterSymbol, KaType>()
    val declaredReceiverType = receiverParameter?.returnType
    val classSubstitutor = if (declaredReceiverType != null) {
        if (!collectTypeMappings(declaredReceiverType, receiverType, mappings)) return@runCatching declaredReturnType
        null
    } else {
        // A member spells its class type parameters (`Provided<T>`) while the receiver is the
        // instantiated class (`Delegate<Int>`), so the class's parameters provide the mapping and
        // its instantiation is what the value parameters are spelled with.
        collectClassTypeMappings(receiverType, mappings)
        receiverType.classTypeSubstitutor()
    }
    if (arguments != null && !collectArgumentTypeMappings(this, arguments, classSubstitutor, mappings)) {
        return@runCatching declaredReturnType
    }
    if (mappings.isEmpty()) return@runCatching declaredReturnType
    createSubstitutor(mappings).substitute(declaredReturnType)
}.getOrDefault(returnType)

/**
 * Collects the type-parameter instantiations that the receiver class's arguments supply.
 *
 * A receiver that is not a class type, or whose arity does not match the declaring class's type
 * parameters, contributes nothing and leaves the other sources of inference untouched.
 *
 * @param receiverType instantiated type the callable is invoked on
 * @param mappings accumulator of type-parameter instantiations
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun collectClassTypeMappings(
    receiverType: KaType,
    mappings: MutableMap<KaTypeParameterSymbol, KaType>,
) {
    runCatching {
        val classType = receiverType as? KaClassType ?: return@runCatching
        val classParameters = (classType.expandedSymbol?.psi as? KtClassOrObject)?.typeParameters
            ?: return@runCatching
        val typeParameters = classParameters.mapNotNull { parameter ->
            runCatching { parameter.symbol }.getOrNull()
        }
        val arguments = classType.typeArguments
        if (typeParameters.size != arguments.size) return@runCatching
        typeParameters.forEachIndexed { index, parameter ->
            arguments[index].type?.let { argument -> mappings[parameter] = argument }
        }
    }
}

/**
 * Collects the type-parameter instantiations the implicit call's arguments imply.
 *
 * The provider `operator fun <T> Delegate.provideDelegate(thisRef: T, ...)` declares `T` only in a
 * value parameter, so the delegate type instantiates nothing and only the property's `thisRef`
 * tells that the provider returns `Provided<Host>`. A null literal constrains no type parameter —
 * a top-level property passes `null` as `thisRef` — so it contributes no mapping. A vararg call
 * passes a different number of arguments than the declaration has parameters, which positional
 * unification cannot express, so such a call leaves the inference untouched.
 *
 * The receiver may already have instantiated some type parameters (`Delegate<Base>` fixes
 * `T = Base`). Those instantiations are substituted into the parameter types first, so an argument
 * only has to be assignable to the parameter the receiver instantiated instead of contradicting
 * the receiver: `Host : Base` still passes `T = Base` to
 * `provideDelegate(thisRef: T)`.
 *
 * @param callable callable whose value parameters are unified
 * @param arguments argument types of the implicit call
 * @param classSubstitutor instantiation of the declaring class, or `null` for an extension
 * @param mappings accumulator of type-parameter instantiations
 * @return `false` when the same type parameter would need two different instantiations
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun collectArgumentTypeMappings(
    callable: KaCallableSymbol,
    arguments: List<KaType?>,
    classSubstitutor: KaSubstitutor?,
    mappings: MutableMap<KaTypeParameterSymbol, KaType>,
): Boolean {
    val parameters = (callable as? KaFunctionSymbol)?.valueParameters ?: return true
    if (parameters.size != arguments.size) return true
    val knownSubstitutor = if (mappings.isEmpty()) {
        null
    } else {
        runCatching { createSubstitutor(mappings.toMap()) }.getOrNull()
    }
    val parameterTypes = parameters.map { parameter ->
        val declaredType = parameter.returnType
        listOfNotNull(classSubstitutor, knownSubstitutor).fold(declaredType) { type, substitutor ->
            runCatching { substitutor.substitute(type) }.getOrDefault(type)
        }
    }
    return arguments.withIndex().all { (index, argument) ->
        if (argument == null || argument.semanticallyEquals(builtinTypes.nullableNothing)) return@all true
        val parameterType = parameterTypes[index]
        // A parameter type whose type parameters are all instantiated is concrete: the argument
        // only has to be assignable, and requiring an exact match would reject a subtype.
        if (parameterType.mentionsTypeParameter()) {
            collectTypeMappings(parameterType, argument, mappings)
        } else {
            acceptsArgument(parameterType, argument)
        }
    }
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
 * operation. That value is the compound operator's result, not the getter's result: for a member
 * `get(Int): Initial` and an extension `Initial.plus(Int): Updated`, `box[0] += 1` passes
 * `Updated`, which selects a different setter overload than `Initial` would. When the operator
 * cannot be resolved, the getter's type stands in for it, which still distinguishes index and
 * genuinely unrelated value types.
 *
 * @return index types followed by the type of the assigned value, or `null` when unresolved
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
    val receiverType = runCatching { arrayExpression?.expressionType }.getOrNull()
    val getterType = receiverType
        ?.let { type -> runCatching { getter.returnTypeInferredFrom(type) }.getOrNull() }
        ?: runCatching { getter.returnType }.getOrNull()
    return indices + assignedValueType(getterType)
}

/**
 * Returns the type of the value a compound indexed write assigns through `set`.
 *
 * A binary compound assignment is resolved as the implicit `set` call, so the resolved operation is
 * the selected setter and its value parameter type is the one the argument was accepted by. A
 * prefix or postfix increment resolves to `inc`/`dec` instead, whose instantiated return type is
 * the assigned value. The getter's type stands in only when the operation cannot be resolved.
 *
 * @param getterType return type of the getter the write reads
 * @return type of the assigned value, or `null` when it cannot be resolved
 */
context(_: KaSession)
private fun KtArrayAccessExpression.assignedValueType(getterType: KaType?): KaType? {
    val operationReference = when (val parent = parent) {
        is KtBinaryExpression -> parent.operationReference.takeIf { parent.left == this }
        is KtUnaryExpression -> parent.operationReference.takeIf { parent.baseExpression == this }
        else -> null
    } ?: return getterType
    val operation = runCatching {
        operationReference.references.filterIsInstance<KtReference>()
            .flatMap { reference -> reference.resolveToSymbols().orEmpty() }
            .filterIsInstance<KaCallableSymbol>()
            .firstOrNull()
    }.getOrNull() ?: return getterType
    if ((operation as? KaNamedSymbol)?.name?.asString() == "set") {
        return (operation as? KaFunctionSymbol)?.valueParameters?.lastOrNull()?.returnType
            ?: getterType
    }
    return getterType
        ?.let { type -> runCatching { operation.returnTypeInferredFrom(type) }.getOrNull() }
        ?: getterType
}

/**
 * Returns the type of the implicit `thisRef` argument a delegated property's accessors receive.
 *
 * An extension property passes its extension receiver, a member property passes its enclosing
 * instance, and a top-level or local property passes `null`. The compiler ignores accessor
 * overloads whose first parameter cannot take that argument, so selecting an operator on the
 * receiver type alone can pick an inapplicable overload.
 *
 * @return static type of the implicit `thisRef` argument
 */
context(_: KaSession)
internal fun KtProperty.delegationThisRefType(): KaType =
    receiverTypeReference?.type ?: enclosingInstanceType() ?: builtinTypes.nullableNothing

/**
 * Returns the type of the implicit `thisRef` argument `provideDelegate` receives.
 *
 * The provider runs while the delegate is created, before any extension receiver exists: the
 * compiler passes the dispatch receiver only, so a top-level or extension property passes `null`
 * where its accessors would pass the extension receiver. Member properties still pass their
 * enclosing instance.
 *
 * @return static type of the implicit provider `thisRef` argument
 */
context(_: KaSession)
internal fun KtProperty.providerThisRefType(): KaType =
    enclosingInstanceType() ?: builtinTypes.nullableNothing

/**
 * Returns the type of the instance a member property passes as `thisRef`.
 *
 * A local property is not a member of the class that lexically contains it: the compiler passes
 * `null` for it even though [containingClassOrObject] finds that class.
 *
 * @return type of the enclosing instance, or `null` when the property passes none
 */
context(_: KaSession)
private fun KtProperty.enclosingInstanceType(): KaType? =
    if (isLocal) {
        null
    } else {
        containingClassOrObject?.let { enclosing ->
            runCatching { (enclosing.symbol as? KaClassSymbol)?.defaultType }.getOrNull()
        }
    }

/**
 * Returns the argument types a delegated property passes to one of its operators.
 *
 * `provideDelegate` and `getValue` receive `thisRef` and the `KProperty` instance; `setValue` also
 * receives the assigned value, whose type is the property's own type. The `thisRef` and the value
 * decide which overloads the compiler can select at all. The provider is the one operator that
 * never receives an extension receiver.
 *
 * @param operatorName `provideDelegate`, `getValue`, or `setValue`
 * @return argument types in call order; `null` entries are unresolved expressions
 */
context(_: KaSession)
internal fun KtProperty.delegationArgumentTypes(operatorName: String): List<KaType?> {
    val thisRef =
        if (operatorName == "provideDelegate") providerThisRefType() else delegationThisRefType()
    return if (operatorName == "setValue") listOf(thisRef, null, propertyValueType()) else listOf(thisRef, null)
}

/**
 * Returns the type of the value assigned through this property.
 *
 * A delegated setter receives exactly the property type, so an accessor declared with an
 * unrelated value type (`setValue(..., Int)` for a `String` property) is inapplicable and must not
 * hide the applicable extension.
 *
 * @return declared or inferred property type, or `null` when it cannot be resolved
 */
context(_: KaSession)
private fun KtProperty.propertyValueType(): KaType? =
    runCatching { (symbol as? KaVariableSymbol)?.returnType }.getOrNull()

/**
 * Tells whether this callable accepts the arguments of an indexed write or a delegate call.
 *
 * An indexed write passes the indices and then the assigned value. Kotlin allows `vararg` in any
 * parameter position (`fun set(vararg indices: Int, value: Int)`), so every argument takes the
 * type of the parameter it lands on: arguments before the vararg match their own parameter,
 * arguments after it match the trailing fixed parameters, and the rest match the vararg element
 * type. A parameter whose type is a type parameter or cannot be compared is accepted, because the
 * alternative — treating it as inapplicable — would import an extension the member already
 * shadows. A declared scope is not instantiated, so a [substitutor] replacing the declared type
 * parameters — the declaring class's for a member, the receiver's for an extension — with the
 * actual type arguments is applied to every parameter type first.
 *
 * An indexed write may also omit index parameters:
 * `operator fun set(i: Int, j: Int = 0, value: Int)` serves `box[0] = 1` by binding the index to
 * `i`, the assigned value to the last parameter and leaving the defaulted `j` out. Such a call
 * passes fewer arguments than the declaration has parameters, so [indexedWrite] binds the assigned
 * value to the final parameter and requires every parameter between the supplied indices and it to
 * have a default. A delegate call always passes one argument per parameter and keeps the positional
 * rule.
 *
 * @param arguments argument types in call order; `null` entries are unresolved expressions
 * @param substitutor instantiates the type parameters the parameter types are spelled with
 * @param indexedWrite `true` for the arguments of an indexed write, which always bind the assigned
 *   value to the final parameter and may omit defaulted index parameters
 * @return `true` when the callable can be selected for those arguments
 */
context(_: KaSession)
internal fun KaCallableSymbol.acceptsIndexedSetterArguments(
    arguments: List<KaType?>,
    substitutor: KaSubstitutor? = null,
    indexedWrite: Boolean = false,
): Boolean = runCatching {
    val function = this as? KaFunctionSymbol ?: return@runCatching false
    val parameters = function.valueParameters
    if (parameters.isEmpty()) return@runCatching false
    val parameterTypes = parameters.map { parameter ->
        val declaredType = parameter.returnType
        substitutor?.let { typeSubstitutor ->
            runCatching { typeSubstitutor.substitute(declaredType) }.getOrDefault(declaredType)
        } ?: declaredType
    }
    val varargIndex = parameters.indexOfFirst { it.isVararg }
    if (indexedWrite && varargIndex < 0) {
        return@runCatching acceptsIndexedWrite(arguments, parameters, parameterTypes)
    }
    if (varargIndex < 0) {
        if (arguments.size != parameters.size) return@runCatching false
        return@runCatching arguments.withIndex().all { (index, argument) ->
            argument == null || acceptsArgument(parameterTypes[index], argument)
        }
    }
    // The vararg may take any number of arguments, including none, so only the fixed parameters
    // constrain the argument count.
    if (arguments.size < parameters.size - 1) return@runCatching false
    val trailingCount = parameters.size - varargIndex - 1
    val varargEnd = arguments.size - trailingCount
    arguments.withIndex().all { (index, argument) ->
        val parameterIndex = when {
            index < varargIndex -> index
            index >= varargEnd -> index - varargEnd + varargIndex + 1
            else -> varargIndex
        }
        argument == null || acceptsArgument(parameterTypes[parameterIndex], argument)
    }
}.getOrDefault(false)

/**
 * Tells whether these parameters accept the indices and the assigned value of an indexed write.
 *
 * The assigned value always takes the final parameter and the indices fill the leading parameters
 * in order, so the parameters in between are the ones the call omits and each of them must have a
 * default. `set(i: Int, j: Int, value: Int)` therefore rejects `box[0] = 1`, while
 * `set(i: Int, j: Int = 0, value: Int)` accepts it; a setter without any index parameter rejects
 * every write.
 *
 * @param arguments index types followed by the assigned value's type; `null` entries are unresolved
 * @param parameters declared value parameters of the setter
 * @param parameterTypes instantiated types of [parameters]
 * @return `true` when the write can be selected
 */
context(_: KaSession)
private fun acceptsIndexedWrite(
    arguments: List<KaType?>,
    parameters: List<KaValueParameterSymbol>,
    parameterTypes: List<KaType>,
): Boolean {
    if (arguments.isEmpty()) return false
    val indexCount = arguments.size - 1
    if (indexCount > parameters.size - 1) return false
    if (parameters.subList(indexCount, parameters.size - 1).any { parameter -> !parameter.hasDefaultValue }) {
        return false
    }
    val indicesMatch = (0 until indexCount).all { index ->
        val argument = arguments[index] ?: return@all true
        acceptsArgument(parameterTypes[index], argument)
    }
    if (!indicesMatch) return false
    val value = arguments.last() ?: return true
    return acceptsArgument(parameterTypes.last(), value)
}

/**
 * Returns the substitutor mapping this type's class parameters to its actual type arguments.
 *
 * The member scope of a class symbol is declared, not instantiated: the setter of `Grid<T>` is
 * reported with the parameter type `T`, whose bound `Any?` would accept every argument. Only
 * substituting `T` with the receiver's `String` tells that `set(String, Int)` cannot take
 * `grid[0]++`. Types that are not a class type keep their declared view.
 *
 * @return the substitutor, or `null` when the type arguments cannot be read
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KaType.classTypeSubstitutor(): KaSubstitutor? = runCatching {
    val classSymbol = expandedSymbol as? KaClassSymbol ?: return@runCatching null
    val typeArguments = (this as? KaClassType)
        ?.takeIf { classType -> classType.classId == classSymbol.classId }
        ?.typeArguments
        ?: return@runCatching null
    val parameters = classSymbol.typeParameters
    if (parameters.size != typeArguments.size) return@runCatching null
    val mapping = parameters.mapIndexedNotNull { index, parameter ->
        val argument = typeArguments[index].type ?: return@mapIndexedNotNull null
        parameter to argument
    }
    if (mapping.size != parameters.size) return@runCatching null
    createSubstitutor(mapping.toMap())
}.getOrNull()

/**
 * Tells whether a value parameter of [parameterType] takes [argument].
 *
 * A type parameter is not a wildcard: `T : Host` accepts only arguments that satisfy `Host`, so
 * its bounds decide applicability. Treating every type parameter as applicable selects an
 * overload the compiler skips and hides the one that is actually bound. A bound that itself
 * mentions a type parameter (`T : Comparable<R>`) cannot be compared without instantiating it, so
 * that bound stays inconclusive instead of rejecting a possibly valid argument. The nullability of
 * the occurrence matters as well: `T?` accepts null even when the bound of `T` is non-null, and it
 * accepts a nullable argument (`T?` with `T : Host` takes `Host?`), so only the argument's
 * non-nullable part faces the bounds.
 *
 * @param parameterType declared parameter type
 * @param argument actual argument type
 * @return `true` when assignability holds or cannot be decided
 */
context(_: KaSession)
private fun acceptsArgument(parameterType: KaType, argument: KaType): Boolean = runCatching {
    if (parameterType is KaTypeParameterType) {
        val nullableOccurrence = parameterType.nullability == KaTypeNullability.NULLABLE
        if (nullableOccurrence && argument.semanticallyEquals(builtinTypes.nullableNothing)) {
            return@runCatching true
        }
        val checkedArgument =
            if (nullableOccurrence && argument.nullability == KaTypeNullability.NULLABLE) {
                argument.asNonNullable() ?: argument
            } else {
                argument
            }
        parameterType.symbol.upperBounds.all { bound ->
            bound.mentionsTypeParameter() || acceptsArgument(bound, checkedArgument)
        }
    } else {
        argument.isSubtypeOf(parameterType)
    }
}.getOrDefault(true)

/**
 * Rebuilds this type without its nullability marker.
 *
 * A nullable occurrence `T?` accepts a nullable argument as long as the argument's non-nullable
 * part satisfies the bounds of `T`. The analysis API exposes no nullability setter on an existing
 * type, so the type is rebuilt from its class or type-parameter symbol.
 *
 * @return the non-nullable counterpart of this type, or `null` when it cannot be rebuilt
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaType.asNonNullable(): KaType? = runCatching {
    when (this) {
        is KaClassType -> buildClassType(classId) {
            typeArguments.forEach { projection -> argument(projection) }
            nullability = KaTypeNullability.NON_NULLABLE
        }
        is KaTypeParameterType -> buildTypeParameterType(symbol) {
            nullability = KaTypeNullability.NON_NULLABLE
        }
        else -> null
    }
}.getOrNull()

/**
 * Tells whether a type parameter appears anywhere in this type's structure.
 *
 * A bound such as `Comparable<T>` cannot be checked against a concrete argument without
 * instantiating `T`; a raw subtype test would reject a valid argument, so such a bound is treated
 * as inconclusive.
 *
 * @return `true` when this type mentions a type parameter
 */
private fun KaType.mentionsTypeParameter(): Boolean = when (this) {
    is KaTypeParameterType -> true
    is KaClassType -> typeArguments.any { projection -> projection.type?.mentionsTypeParameter() == true }
    is KaFunctionType -> receiverType?.mentionsTypeParameter() == true ||
        parameterTypes.any { it.mentionsTypeParameter() } ||
        returnType.mentionsTypeParameter()
    is KaCapturedType -> projection.type?.mentionsTypeParameter() == true
    is KaDefinitelyNotNullType -> original.mentionsTypeParameter()
    is KaFlexibleType -> lowerBound.mentionsTypeParameter() || upperBound.mentionsTypeParameter()
    is KaIntersectionType -> conjuncts.any { it.mentionsTypeParameter() }
    else -> false
}

/**
 * Selects the member overload the compiler binds for a convention call.
 *
 * Members have no extension receiver, so overload resolution compares their value parameter
 * tuples: `provideDelegate(Host, KProperty<*>)` is preferred over
 * `provideDelegate(Any?, KProperty<*>)` for a `Host` thisRef. The selected overload decides the
 * provider's result type and therefore the accessor lookup, so returning the first declared match
 * would make the accessor selection use an unrelated type.
 *
 * @param candidates member callables visible under the same simple name
 * @param arguments argument types of the implicit call
 * @param substitutor maps the declaring class's type parameters to the receiver's arguments
 * @param indexedWrite `true` when [arguments] belong to an indexed write, whose assigned value
 *   binds to the final parameter and whose defaulted index parameters may be omitted
 * @return the selected member, or `null` when none applies
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun selectMember(
    candidates: List<KaCallableSymbol>,
    arguments: List<KaType?>,
    substitutor: KaSubstitutor? = null,
    indexedWrite: Boolean = false,
): KaCallableSymbol? {
    val applicable = candidates.filter { candidate ->
        candidate.acceptsIndexedSetterArguments(arguments, substitutor, indexedWrite)
    }
    return applicable.firstOrNull { candidate ->
        applicable.none { other -> other !== candidate && other.parametersAreMoreSpecificThan(candidate) }
    } ?: applicable.firstOrNull()
}

/**
 * Selects the extension the compiler binds for a convention call.
 *
 * Every candidate is visible through the same package scopes, so overload resolution ranks them
 * by receiver specificity: `SubBox.set` wins over `Box.set` for a `SubBox` receiver. Candidates
 * that cannot take [arguments] are dropped first, which separates overloads declared on the same
 * receiver type; among those, the more specific parameter tuple wins, because `Int` fits a
 * `Number` parameter as well.
 *
 * @param candidates extensions visible under the same simple name
 * @param receiverType type of the value the operator is invoked on
 * @param arguments argument types of the call, or `null` when they cannot be resolved
 * @param indexedWrite `true` when [arguments] belong to an indexed write, whose assigned value
 *   binds to the final parameter and whose defaulted index parameters may be omitted
 * @return the selected extension, or `null` when none applies
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun selectExtension(
    candidates: List<KaCallableSymbol>,
    receiverType: KaType,
    arguments: List<KaType?>? = null,
    indexedWrite: Boolean = false,
): KaCallableSymbol? {
    val applicable = candidates.filter { candidate ->
        runCatching { candidate.canBeCalledAsExtensionOn(receiverType) }.getOrDefault(false) &&
            (arguments == null ||
                candidate.acceptsIndexedSetterArguments(
                    arguments,
                    candidate.receiverTypeSubstitutor(receiverType),
                    indexedWrite,
                ))
    }
    return applicable.firstOrNull { candidate ->
        applicable.none { other -> other !== candidate && other.isMoreSpecificThan(candidate, receiverType) }
    } ?: applicable.firstOrNull()
}

/**
 * Returns the substitutor instantiating this extension's type parameters from [receiverType].
 *
 * An extension spells its value parameters with the type parameters of its declaring receiver
 * (`operator fun <T> Grid<T>.set(index: T, value: Int)`), so `T : Any?` accepts every argument
 * until the receiver type supplies the instantiation. For a `Grid<String>` receiver the setter
 * takes a `String` index and cannot serve `grid[0]++`, whose index is an `Int`. A declared
 * receiver that is not a class type supplies no instantiation.
 *
 * @param receiverType actual type the extension is invoked on
 * @return the substitutor, or `null` when the receiver instantiates no type parameter
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KaCallableSymbol.receiverTypeSubstitutor(receiverType: KaType): KaSubstitutor? = runCatching {
    val declaredReceiverType = receiverParameter?.returnType ?: return@runCatching null
    val mappings = linkedMapOf<KaTypeParameterSymbol, KaType>()
    if (!collectTypeMappings(declaredReceiverType, receiverType, mappings)) return@runCatching null
    if (mappings.isEmpty()) return@runCatching null
    createSubstitutor(mappings)
}.getOrNull()

/**
 * Tells whether this callable's overload is preferred over [other] for [receiverType].
 *
 * The receivers are compared as they are instantiated for the actual receiver, because a generic
 * declared receiver (`Box<T>`) has no subtype relation with a concrete one (`Box<String>`) until
 * the receiver supplies `T`. Two overloads whose instantiated receivers agree are ranked by their
 * type parameters: the concrete receiver needs fewer of them, and Kotlin prefers it over the
 * generic one. When both need as many, the preference falls back to the value parameters:
 * `Box.set(Int, Int)` is more specific than `Box.set(Int, Number)` for a write whose value is an
 * `Int`.
 *
 * @param other competing callable
 * @param receiverType type of the value the operator is invoked on
 * @return `true` when this callable is a strictly better overload
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaCallableSymbol.isMoreSpecificThan(
    other: KaCallableSymbol,
    receiverType: KaType,
): Boolean = runCatching {
    val receiver = instantiatedReceiverType(receiverType) ?: return@runCatching false
    val otherReceiver = other.instantiatedReceiverType(receiverType) ?: return@runCatching false
    if (!receiver.semanticallyEquals(otherReceiver)) {
        return@runCatching receiver.isSubtypeOf(otherReceiver) && !otherReceiver.isSubtypeOf(receiver)
    }
    val ownParameters = (this as? KaFunctionSymbol)?.typeParameters?.size ?: 0
    val otherParameters = (other as? KaFunctionSymbol)?.typeParameters?.size ?: 0
    if (ownParameters != otherParameters) return@runCatching ownParameters < otherParameters
    parametersAreMoreSpecificThan(other)
}.getOrDefault(false)

/**
 * Returns this extension's declared receiver as instantiated for [receiverType].
 *
 * `operator fun <T> Box<T>.getValue(...)` declares the receiver `Box<T>`, and only the actual
 * `Box<String>` receiver turns it into the `Box<String>` the compiler compares. A declaration that
 * instantiates no type parameter keeps its declared receiver.
 *
 * @param receiverType type of the value the extension is invoked on
 * @return the instantiated receiver, or `null` when there is none
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaCallableSymbol.instantiatedReceiverType(receiverType: KaType): KaType? = runCatching {
    val declaredReceiver = receiverParameter?.returnType ?: return@runCatching null
    receiverTypeSubstitutor(receiverType)?.let { substitutor ->
        runCatching { substitutor.substitute(declaredReceiver) }.getOrDefault(declaredReceiver)
    } ?: declaredReceiver
}.getOrNull()

/**
 * Tells whether this callable's value parameters are strictly more specific than [other]'s.
 *
 * Every parameter must accept the other's type, and at least one must be a proper subtype:
 * `(Int, Int)` is more specific than `(Int, Number)`. A type parameter or a differing arity
 * leaves the comparison undecided, because the instantiation is what would decide it.
 *
 * @param other competing callable
 * @return `true` when this callable takes a strictly narrower parameter tuple
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaCallableSymbol.parametersAreMoreSpecificThan(other: KaCallableSymbol): Boolean = runCatching {
    val mine = (this as? KaFunctionSymbol)?.valueParameters ?: return@runCatching false
    val theirs = (other as? KaFunctionSymbol)?.valueParameters ?: return@runCatching false
    if (mine.size != theirs.size || mine.isEmpty()) return@runCatching false
    var strict = false
    val fits = mine.indices.all { index ->
        val mineType = mine[index].returnType
        val theirType = theirs[index].returnType
        if (mineType is KaTypeParameterType || theirType is KaTypeParameterType) return@all false
        if (!mineType.isSubtypeOf(theirType)) return@all false
        if (!theirType.isSubtypeOf(mineType)) strict = true
        true
    }
    fits && strict
}.getOrDefault(false)

/**
 * Tells whether this indexed access is updated implicitly, not merely assigned or read.
 *
 * A plain assignment (`box[0] = 1`) resolves its `set` through the array-access reference, but
 * an increment or compound assignment (`box[0]++`, `box[0] += 1`) lowers to `get` then `set`
 * and exposes only `get`.
 *
 * @return `true` when the compiler resolves a setter for this access implicitly
 */
internal fun KtArrayAccessExpression.isCompoundWrite(): Boolean {
    val parent = parent
    return when (parent) {
        is KtBinaryExpression -> parent.left == this &&
            parent.operationToken in KtTokens.ALL_ASSIGNMENTS && parent.operationToken != KtTokens.EQ
        is KtUnaryExpression -> parent.baseExpression == this &&
            parent.operationToken in setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)
        else -> false
    }
}

/**
 * Returns the `set` extension a compound indexed write implicitly selects.
 *
 * The access exposes only `get`, so the setter is looked up through the file's real scope. Scopes
 * are visited in priority order and the most specific applicable `set` wins inside each of them,
 * mirroring overload resolution. A member setter always wins over extensions and cannot be
 * captured by an import.
 *
 * @return the selected `set` extension, `null` for a plain read or a member setter
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KtArrayAccessExpression.selectedIndexedSetter(): KaCallableSymbol? {
    if (!isCompoundWrite()) return null
    val receiverType = runCatching { arrayExpression?.expressionType }.getOrNull() ?: return null
    val arguments = setterArgumentTypes()
    // A member setter wins only when its signature accepts the actual indices and value; a
    // misfitting overload leaves the write to an applicable extension.
    if (receiverType.hasApplicableMemberIndexedSetter(arguments)) return null
    val identifier = Name.identifier("set")
    containingKtFile.scopeContext(this).scopes.forEach { scopeWithKind ->
        val candidates = scopeWithKind.scope.callables(identifier)
            .filter { symbol -> symbol.isExtension }
            .toList()
        selectExtension(candidates, receiverType, arguments, indexedWrite = true)?.let { return it }
    }
    return null
}

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
    val substitutor = classTypeSubstitutor()
    expandedSymbol?.memberScope?.declarations
        .orEmpty()
        .filterIsInstance<KaFunctionSymbol>()
        .any { symbol ->
            (symbol as? KaNamedSymbol)?.name?.asString() == "set" &&
                symbol.acceptsIndexedSetterArguments(arguments, substitutor, indexedWrite = true)
        }
}.getOrDefault(true)
