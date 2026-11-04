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
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.analysis.api.types.KaCapturedType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
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
 * Returns this callable's return type with its type arguments inferred from [receiverType].
 *
 * A generic extension declares its return type in terms of its own type parameters
 * (`operator fun <T> Delegate<T>.provideDelegate(...): Provided<T>`), while the compiler
 * instantiates them from the receiver. The delegated property's accessors are selected on
 * `Provided<Int>` for a `Delegate<Int>` delegate expression, so the declared `Provided<T>` cannot
 * be used directly. Unifying the declared receiver with the actual one yields the mapping.
 *
 * A callable without a receiver keeps its declared return type; unifyable structures also fall
 * back to it, because guessing an instantiation would select the wrong operator. A member
 * provider has no extension receiver, so its type arguments come from the instantiated scope of
 * the receiver type: `Delegate<T>.provideDelegate` selected on `Delegate<Int>` returns
 * `Provided<Int>`.
 *
 * @param receiverType actual type the callable is invoked on
 * @return the return type with the inferred type arguments substituted
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KaCallableSymbol.returnTypeInferredFrom(receiverType: KaType): KaType {
    val declaredReceiverType = receiverParameter?.returnType
        ?: return memberReturnTypeInferredFrom(receiverType)
    return runCatching {
        val mappings = linkedMapOf<KaTypeParameterSymbol, KaType>()
        if (!collectTypeMappings(declaredReceiverType, receiverType, mappings)) return@runCatching returnType
        if (mappings.isEmpty()) return@runCatching returnType
        createSubstitutor(mappings).substitute(returnType)
    }.getOrDefault(returnType)
}

/**
 * Returns this member's return type with the receiver class's arguments substituted.
 *
 * A member symbol spells its class type parameters (`Provided<T>`), while the receiver type is
 * the instantiated class (`Delegate<Int>`). Mapping the class declaration's parameters to the
 * receiver's arguments gives the instantiation the accessors are selected on. A receiver that is
 * not the declaring class (an inherited member) keeps the declared return type.
 *
 * @param receiverType instantiated type the member is invoked on
 * @return the return type with the class's type arguments substituted
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaCallableSymbol.memberReturnTypeInferredFrom(receiverType: KaType): KaType = runCatching {
    val classType = receiverType as? KaClassType ?: return@runCatching returnType
    val classParameters = (classType.expandedSymbol?.psi as? KtClassOrObject)?.typeParameters
        ?: return@runCatching returnType
    val typeParameters = classParameters.mapNotNull { parameter ->
        runCatching { parameter.symbol }.getOrNull()
    }
    val arguments = classType.typeArguments
    if (typeParameters.size != arguments.size) return@runCatching returnType
    val mappings = typeParameters.mapIndexedNotNull { index, parameter ->
        arguments[index].type?.let { argument -> parameter to argument }
    }.toMap()
    if (mappings.isEmpty()) return@runCatching returnType
    createSubstitutor(mappings).substitute(returnType)
}.getOrDefault(returnType)

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
 * receives the assigned value. The value's type is unknown here, so it is left unchecked, while
 * the `thisRef` type decides which overloads the compiler can select at all. The provider is the
 * one operator that never receives an extension receiver.
 *
 * @param operatorName `provideDelegate`, `getValue`, or `setValue`
 * @return argument types in call order; `null` entries are unresolved expressions
 */
context(_: KaSession)
internal fun KtProperty.delegationArgumentTypes(operatorName: String): List<KaType?> {
    val thisRef =
        if (operatorName == "provideDelegate") providerThisRefType() else delegationThisRefType()
    return if (operatorName == "setValue") listOf(thisRef, null, null) else listOf(thisRef, null)
}

/**
 * Tells whether this callable accepts the arguments of an indexed write or a delegate call.
 *
 * An indexed write passes the indices and then the assigned value. Kotlin allows `vararg` in any
 * parameter position (`fun set(vararg indices: Int, value: Int)`), so every argument takes the
 * type of the parameter it lands on: arguments before the vararg match their own parameter,
 * arguments after it match the trailing fixed parameters, and the rest match the vararg element
 * type. A parameter whose type is a type parameter or cannot be compared is accepted, because the
 * alternative — treating it as inapplicable — would import an extension the member already
 * shadows.
 *
 * @param arguments argument types in call order; `null` entries are unresolved expressions
 * @return `true` when the callable can be selected for those arguments
 */
context(_: KaSession)
internal fun KaCallableSymbol.acceptsIndexedSetterArguments(arguments: List<KaType?>): Boolean = runCatching {
    val function = this as? KaFunctionSymbol ?: return@runCatching false
    val parameters = function.valueParameters
    if (parameters.isEmpty()) return@runCatching false
    val varargIndex = parameters.indexOfFirst { it.isVararg }
    if (varargIndex < 0) {
        if (arguments.size != parameters.size) return@runCatching false
        return@runCatching arguments.withIndex().all { (index, argument) ->
            argument == null || acceptsArgument(parameters[index].returnType, argument)
        }
    }
    // The vararg may take any number of arguments, including none, so only the fixed parameters
    // constrain the argument count.
    if (arguments.size < parameters.size - 1) return@runCatching false
    val trailingCount = parameters.size - varargIndex - 1
    val varargEnd = arguments.size - trailingCount
    arguments.withIndex().all { (index, argument) ->
        val parameter = when {
            index < varargIndex -> parameters[index]
            index >= varargEnd -> parameters[index - varargEnd + varargIndex + 1]
            else -> parameters[varargIndex]
        }
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
 * receiver type; among those, the more specific parameter tuple wins, because `Int` fits a
 * `Number` parameter as well.
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
        applicable.none { other -> other !== candidate && other.isMoreSpecificThan(candidate) }
    } ?: applicable.firstOrNull()
}

/**
 * Tells whether this callable's overload is preferred over [other] by the compiler.
 *
 * A more specific receiver wins first. When both are declared on the same receiver type, the
 * preference falls back to the value parameters: `Box.set(Int, Int)` is more specific than
 * `Box.set(Int, Number)` for a write whose value is an `Int`.
 *
 * @param other competing callable
 * @return `true` when this callable is a strictly better overload
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaCallableSymbol.isMoreSpecificThan(other: KaCallableSymbol): Boolean =
    receiverIsMoreSpecificThan(other) ||
        (hasSameReceiverAs(other) && parametersAreMoreSpecificThan(other))

/**
 * Tells whether both callables declare the same receiver type.
 *
 * @param other competing callable
 * @return `true` when the declared receivers are equivalent
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaCallableSymbol.hasSameReceiverAs(other: KaCallableSymbol): Boolean = runCatching {
    val receiver = receiverParameter?.returnType ?: return@runCatching false
    val otherReceiver = other.receiverParameter?.returnType ?: return@runCatching false
    receiver.semanticallyEquals(otherReceiver)
}.getOrDefault(false)

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
        selectExtension(candidates, receiverType, arguments)?.let { return it }
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
    expandedSymbol?.memberScope?.declarations
        .orEmpty()
        .filterIsInstance<KaFunctionSymbol>()
        .any { symbol ->
            (symbol as? KaNamedSymbol)?.name?.asString() == "set" &&
                symbol.acceptsIndexedSetterArguments(arguments)
        }
}.getOrDefault(true)
