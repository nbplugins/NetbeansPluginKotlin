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

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.buildTypeParameterType
import org.jetbrains.kotlin.analysis.api.components.createSubstitutor
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.components.type
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.symbol
import org.jetbrains.kotlin.analysis.api.types.KaCapturedType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaIntersectionType
import org.jetbrains.kotlin.analysis.api.types.KaSubstitutor
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtTypeParameter

/**
 * Decides whether a top-level function would collide with a constructor of a renamed class.
 *
 * Renaming a class writes its new name verbatim, so a same-package function whose call signature
 * matches a constructor becomes a conflicting overload that Kotlin rejects. Parameter types are
 * compared as resolved types, because `Int` and a fully qualified `kotlin.Int` are the same Kotlin
 * type even though their source spellings differ. Type parameters are matched by the role they play
 * in the compared signature and then substituted by their counterparts, and the bounds of those the
 * value parameters actually use are compared as well.
 */
object KotlinConstructorSignatureCheck {

    /**
     * Tells whether [function] has the same call signature as a constructor of [primaryClass].
     *
     * Every constructor counts, not only the primary one: a class that declares only secondary
     * constructors has no primary constructor at all, while a class that declares none gets an
     * implicit no-argument primary constructor. An interface declares no constructors, so no
     * function can collide with it. An extension function has an implicit receiver and
     * therefore cannot conflict with a constructor. A parameter whose type cannot be resolved makes
     * the comparison inconclusive, which is reported as a conflict so the rename is rejected
     * instead of producing uncompilable code. The vararg status of each parameter is part of the
     * signature, because Kotlin treats `(vararg Int)` and `(Int)` as distinct overloads.
     *
     * @param primaryClass class whose constructors the rename would introduce
     * @param function same-package function that would share the class's new name
     * @return `true` when a call could not tell a constructor and the function apart
     */
    fun conflictsWithRenamedConstructor(primaryClass: KtClass, function: KtNamedFunction): Boolean {
        if (function.receiverTypeReference != null) return false
        val matching = constructorSignatures(primaryClass)
            .filter { parameters -> parameters.size == function.valueParameters.size }
        if (matching.isEmpty()) return false
        return analyze(function) {
            matching.any { parameters ->
                // A vararg and a fixed parameter of the same type are distinct overloads in
                // Kotlin, so a constructor cannot collide with a function whose vararg status
                // differs at that position.
                val varargsMatch = parameters.indices.all { index ->
                    parameters[index].hasModifier(KtTokens.VARARG_KEYWORD) ==
                        function.valueParameters[index].hasModifier(KtTokens.VARARG_KEYWORD)
                }
                if (!varargsMatch) return@any false
                val correspondence = typeParameterCorrespondence(primaryClass, function, parameters)
                    ?: return@any false
                val substitutor = typeParameterSubstitutor(correspondence)
                val typesMatch = parameters.indices.all { index ->
                    val constructorType = runCatching { parameters[index].typeReference?.type }.getOrNull()
                    val functionType = runCatching { function.valueParameters[index].typeReference?.type }.getOrNull()
                    constructorType == null || functionType == null || runCatching {
                        substitutor.substitute(functionType).semanticallyEquals(constructorType)
                    }.getOrDefault(false)
                }
                // Bounds are part of the signature only for the type parameters the matched value
                // parameters actually use; an unused parameter cannot tell two calls apart even
                // when its bounds differ, so it must not hide a real conflict.
                typesMatch &&
                    typeParametersMatch(primaryClass, function, substitutor, parameters, correspondence)
            }
        }
    }

    /**
     * Builds the substitutor replacing the function's type parameters with their class counterparts.
     *
     * @param correspondence function type parameters mapped to the class parameters they stand for
     * @return substitutor replacing the function's type parameters with the class's
     */
    context(_: KaSession)
    private fun typeParameterSubstitutor(
        correspondence: Map<KaTypeParameterSymbol, KaTypeParameterSymbol>,
    ): KaSubstitutor = createSubstitutor(
        correspondence.mapValues { (_, classSymbol) -> buildTypeParameterType(classSymbol) },
    )

    /**
     * Derives which function type parameter stands for which class parameter.
     *
     * Type parameters are identified by their declaration, but the function being compared may
     * declare them in another order: `class Before<A, B>(val x: B, val y: A)` exposes the
     * constructor `After(B, A)` after the rename, and `fun <A, B> After(x: A, y: B)` accepts
     * exactly the same calls once its own parameters are read as `B, A`. The correspondence is
     * therefore read off the compared value parameter types instead of their positions: each pair
     * is unified structurally, recording which function parameter stands for which class
     * parameter. The relation must stay a bijection, so a pair that would map two function
     * parameters to one class parameter — or the other way round — means the signatures differ.
     *
     * A type parameter no value parameter spells keeps its positional counterpart, because an
     * unused parameter cannot distinguish calls while a bound that mentions another parameter still
     * takes part in the comparison, and position is the only relation left for it. A value
     * parameter whose type cannot be resolved supplies no correspondence and keeps that positional
     * pairing as well.
     *
     * @param classOrObject class whose type parameters the function would spell
     * @param function function whose type parameters are mapped
     * @param parameters value parameters of the compared constructor
     * @return function parameter symbol to class parameter symbol, or `null` when a resolved
     *   parameter pair cannot be unified
     */
    context(_: KaSession)
    private fun typeParameterCorrespondence(
        classOrObject: KtClass,
        function: KtNamedFunction,
        parameters: List<KtParameter>,
    ): Map<KaTypeParameterSymbol, KaTypeParameterSymbol>? = runCatching {
        val classSymbols = classOrObject.typeParameters.mapNotNull { parameter ->
            runCatching { parameter.symbol }.getOrNull()
        }.toSet()
        val functionSymbols = function.typeParameters.mapNotNull { parameter ->
            runCatching { parameter.symbol }.getOrNull()
        }.toSet()
        if (classSymbols.isEmpty() && functionSymbols.isEmpty()) return@runCatching emptyMap()
        val mappings = linkedMapOf<KaTypeParameterSymbol, KaTypeParameterSymbol>()
        parameters.indices.forEach { index ->
            val constructorType = runCatching { parameters[index].typeReference?.type }.getOrNull()
                ?: return@forEach
            val functionType = runCatching { function.valueParameters[index].typeReference?.type }.getOrNull()
                ?: return@forEach
            val unified = unifyTypeParameters(
                constructorType,
                functionType,
                classSymbols,
                functionSymbols,
                mappings,
            )
            if (!unified) return@runCatching null
        }
        classOrObject.typeParameters.forEachIndexed { index, classParameter ->
            val classSymbol = runCatching { classParameter.symbol }.getOrNull() ?: return@forEachIndexed
            if (classSymbol in mappings.values) return@forEachIndexed
            val functionSymbol = runCatching { function.typeParameters.getOrNull(index)?.symbol }.getOrNull()
                ?: return@forEachIndexed
            if (functionSymbol !in functionSymbols || functionSymbol in mappings) return@forEachIndexed
            mappings[functionSymbol] = classSymbol
        }
        mappings.toMap()
    }.getOrNull()

    /**
     * Unifies a constructor parameter type with the function's, recording the type parameters.
     *
     * Two type parameters correspond to each other and are recorded; two class types correspond
     * when they denote the same class and their type arguments unify pairwise; anything else
     * corresponds only when the types are semantically equal. A class type parameter facing a
     * concrete type, or a correspondence that would have to be many-to-one, means the signatures
     * differ.
     *
     * @param classType parameter type of the constructor being renamed
     * @param functionType parameter type of the function
     * @param classSymbols type parameters of the class
     * @param functionSymbols type parameters of the function
     * @param mappings correspondence recorded so far, extended in place
     * @return `true` when the types can be the same after renaming the type parameters
     */
    context(_: KaSession)
    private fun unifyTypeParameters(
        classType: KaType,
        functionType: KaType,
        classSymbols: Set<KaTypeParameterSymbol>,
        functionSymbols: Set<KaTypeParameterSymbol>,
        mappings: MutableMap<KaTypeParameterSymbol, KaTypeParameterSymbol>,
    ): Boolean {
        val functionParameter = (functionType as? KaTypeParameterType)
            ?.takeIf { type -> type.symbol in functionSymbols }
        if (functionParameter != null) {
            val classParameter = (classType as? KaTypeParameterType)
                ?.takeIf { type -> type.symbol in classSymbols }
                ?: return false
            if (classParameter.nullability != functionParameter.nullability) return false
            val recorded = mappings[functionParameter.symbol]
            if (recorded != null) return recorded == classParameter.symbol
            if (classParameter.symbol in mappings.values) return false
            mappings[functionParameter.symbol] = classParameter.symbol
            return true
        }
        val classParameterSymbol = (classType as? KaTypeParameterType)?.symbol
        if (classParameterSymbol != null && classParameterSymbol in classSymbols) return false
        if (classType is KaClassType && functionType is KaClassType) {
            if (classType.classId != functionType.classId) return false
            if (classType.nullability != functionType.nullability) return false
            if (classType.typeArguments.size != functionType.typeArguments.size) return false
            return classType.typeArguments.indices.all { index ->
                val classArgument = classType.typeArguments[index].type
                val functionArgument = functionType.typeArguments[index].type
                when {
                    classArgument == null || functionArgument == null ->
                        classArgument == null && functionArgument == null
                    else -> unifyTypeParameters(
                        classArgument,
                        functionArgument,
                        classSymbols,
                        functionSymbols,
                        mappings,
                    )
                }
            }
        }
        return runCatching { classType.semanticallyEquals(functionType) }.getOrDefault(false)
    }

    /**
     * Tells whether corresponding type parameters of a matched signature have equivalent bounds.
     *
     * Two signatures conflict only when their type parameters also allow the same type arguments;
     * a function accepting any `T` would not collide with a constructor restricted to
     * `T : Number`. Only the type parameters the matched value parameters use take part in that
     * comparison: a parameter that never appears in a value parameter cannot distinguish calls,
     * so its bounds must not hide a conflict. A type parameter reached only through another
     * parameter's bound still counts, because a differing bound there narrows the accepted type
     * arguments as well.
     *
     * @param classOrObject class whose type parameter bounds are compared
     * @param function function whose type parameter bounds are compared
     * @param substitutor maps the function's type parameters to the class's
     * @param parameters matched value parameters of the compared signature
     * @param correspondence function type parameters mapped to the class parameters they stand for
     * @return `true` when every relevant corresponding bound is equivalent
     */
    context(_: KaSession)
    private fun typeParametersMatch(
        classOrObject: KtClass,
        function: KtNamedFunction,
        substitutor: KaSubstitutor,
        parameters: List<KtParameter>,
        correspondence: Map<KaTypeParameterSymbol, KaTypeParameterSymbol>,
    ): Boolean {
        val classParameters = classOrObject.typeParameters
        val indexBySymbol = classParameters.mapIndexedNotNull { index, parameter ->
            runCatching { parameter.symbol }.getOrNull()?.let { symbol -> symbol to index }
        }.toMap()
        val functionParameterBySymbol = function.typeParameters.mapNotNull { parameter ->
            runCatching { parameter.symbol }.getOrNull()?.let { symbol -> symbol to parameter }
        }.toMap()
        val functionSymbolByClassSymbol = correspondence.entries.associate { (functionSymbol, classSymbol) ->
            classSymbol to functionSymbol
        }
        fun functionCounterpart(classParameter: KtTypeParameter): KtTypeParameter? {
            val classSymbol = runCatching { classParameter.symbol }.getOrNull() ?: return null
            val functionSymbol = functionSymbolByClassSymbol[classSymbol] ?: return null
            return functionParameterBySymbol[functionSymbol]
        }
        val usedIndices = usedTypeParameterIndices(parameters, indexBySymbol)
            ?: return classParameters.indices.all { index ->
                boundsMatch(classParameters[index], functionCounterpart(classParameters[index]), substitutor)
            }
        val pending = ArrayDeque(usedIndices)
        while (pending.isNotEmpty()) {
            val index = pending.removeFirst()
            val bounds = runCatching { classParameters[index].symbol.upperBounds }.getOrNull().orEmpty()
            bounds.flatMap { bound -> typeParameterIndices(bound, indexBySymbol) }
                .filter { discovered -> usedIndices.add(discovered) }
                .forEach(pending::addLast)
        }
        return usedIndices.all { index ->
            boundsMatch(classParameters[index], functionCounterpart(classParameters[index]), substitutor)
        }
    }

    /**
     * Collects the type parameters the value parameters' types mention directly.
     *
     * @param parameters value parameters of the compared signature
     * @param indexBySymbol class type parameter symbols mapped to their positional index
     * @return mentioned indices, or `null` when a parameter type cannot be resolved
     */
    context(_: KaSession)
    private fun usedTypeParameterIndices(
        parameters: List<KtParameter>,
        indexBySymbol: Map<KaTypeParameterSymbol, Int>,
    ): MutableSet<Int>? {
        val used = mutableSetOf<Int>()
        parameters.forEach { parameter ->
            val type = runCatching { parameter.typeReference?.type }.getOrNull() ?: return null
            used.addAll(typeParameterIndices(type, indexBySymbol))
        }
        return used
    }

    /**
     * Collects the class type parameters a type mentions anywhere in its structure.
     *
     * @param type type to inspect, including nested arguments and function signatures
     * @param indexBySymbol class type parameter symbols mapped to their positional index
     * @return indices of the mentioned class type parameters
     */
    context(_: KaSession)
    private fun typeParameterIndices(
        type: KaType,
        indexBySymbol: Map<KaTypeParameterSymbol, Int>,
    ): Set<Int> {
        val indices = mutableSetOf<Int>()
        fun visit(current: KaType) {
            when (current) {
                is KaFunctionType -> {
                    current.receiverType?.let(::visit)
                    current.parameterTypes.forEach(::visit)
                    visit(current.returnType)
                }
                is KaClassType -> current.typeArguments.forEach { projection ->
                    projection.type?.let(::visit)
                }
                is KaTypeParameterType -> indexBySymbol[current.symbol]?.let(indices::add)
                is KaCapturedType -> current.projection.type?.let(::visit)
                is KaDefinitelyNotNullType -> visit(current.original)
                is KaFlexibleType -> {
                    visit(current.lowerBound)
                    visit(current.upperBound)
                }
                is KaIntersectionType -> current.conjuncts.forEach(::visit)
                else -> {}
            }
        }
        visit(type)
        return indices
    }

    /**
     * Tells whether a class type parameter and its function counterpart allow the same arguments.
     *
     * The bound lists are compared as an unordered set: `where T : Left, T : Right` and
     * `where T : Right, T : Left` impose the same intersection, but declaration order differs and a
     * positional zip would compare `Right` with `Left`. Each substituted function bound therefore
     * consumes a matching class bound, which also keeps duplicated bounds from hiding a real
     * difference.
     *
     * A missing function counterpart or unreadable bounds leave the comparison inconclusive, which
     * keeps the conflict decision conservative instead of hiding a real collision.
     *
     * @param classParameter type parameter of the class being renamed
     * @param functionParameter corresponding type parameter of the function, if any
     * @param substitutor maps the function's type parameters to the class's
     * @return `true` when the bounds are equivalent or cannot be compared
     */
    context(_: KaSession)
    private fun boundsMatch(
        classParameter: KtTypeParameter,
        functionParameter: KtTypeParameter?,
        substitutor: KaSubstitutor,
    ): Boolean {
        functionParameter ?: return true
        val functionBounds = runCatching { functionParameter.symbol.upperBounds }.getOrNull() ?: return true
        val classBounds = runCatching { classParameter.symbol.upperBounds }.getOrNull() ?: return true
        if (functionBounds.size != classBounds.size) return false
        val remaining = classBounds.toMutableList()
        return functionBounds.all { functionBound ->
            val substituted = runCatching { substitutor.substitute(functionBound) }.getOrNull()
                ?: return@all false
            val match = remaining.firstOrNull { classBound ->
                runCatching { substituted.semanticallyEquals(classBound) }.getOrDefault(false)
            } ?: return@all false
            remaining.remove(match)
            true
        }
    }

    /**
     * Returns the parameter list of every constructor the renamed class would expose.
     *
     * A class with a primary constructor uses its parameters; one that declares only secondary
     * constructors has no primary constructor, and a class that declares none gets the implicit
     * no-argument constructor. An interface declares no constructors at all, so it exposes no
     * signature that a same-name function could collide with.
     *
     * @param classOrObject class whose constructors are being compared
     * @return one parameter list per constructor
     */
    private fun constructorSignatures(classOrObject: KtClass): List<List<KtParameter>> {
        val signatures = mutableListOf<List<KtParameter>>()
        val primaryConstructor = classOrObject.primaryConstructor
        when {
            primaryConstructor != null -> signatures += primaryConstructor.valueParameters
            classOrObject.secondaryConstructors.isEmpty() &&
                !classOrObject.isInterface() -> signatures.add(emptyList())
        }
        classOrObject.secondaryConstructors.forEach { constructor -> signatures.add(constructor.valueParameters) }
        return signatures
    }
}
