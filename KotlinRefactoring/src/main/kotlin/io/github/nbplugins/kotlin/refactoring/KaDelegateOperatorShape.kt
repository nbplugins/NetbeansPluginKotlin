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
import org.jetbrains.kotlin.analysis.api.components.defaultType
import org.jetbrains.kotlin.analysis.api.components.defaultTypeWithStarProjections
import org.jetbrains.kotlin.analysis.api.components.expandedSymbol
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.components.memberScope
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.findClass
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaSubstitutor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtModifierListOwner

/**
 * Tells whether this callable has the call shape Kotlin requires of a delegate operator.
 *
 * Delegated properties select their operators by name, but a same-named method takes part only
 * when it is an [operator][isOperatorEligible] *and* its signature fits the delegation protocol:
 * `provideDelegate` and `getValue` take the delegating receiver and the property, while `setValue`
 * adds the assigned value. An ordinary `fun getValue()` is therefore ignored, and so is a
 * non-operator `fun provideDelegate(...)` that only happens to match the parameter shape — Kotlin
 * never calls such a method, so treating it as a provider would switch the accessor lookup to its
 * result type.
 *
 * Kotlin passes a `KProperty` instance to the property parameter, which may be declared with any
 * supertype of it (`Any?`, for example), so the parameter is matched by assignability rather than
 * by its classifier.
 *
 * @param operatorName `provideDelegate`, `getValue`, or `setValue`
 * @return `true` when this callable can be selected as that delegate operator
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KaCallableSymbol.isDelegateOperatorShape(operatorName: String): Boolean {
    val function = this as? KaFunctionSymbol ?: return false
    if (!function.isOperatorEligible()) return false
    val expectedParameters = if (operatorName == "setValue") 3 else 2
    if (function.valueParameters.size != expectedParameters) return false
    return runCatching {
        val propertyType = findClass(K_PROPERTY_CLASS_ID)?.defaultTypeWithStarProjections ?: return@runCatching false
        propertyType.isSubtypeOf(function.valueParameters[1].returnType)
    }.getOrDefault(false)
}

/**
 * Tells whether Kotlin may select this callable for a convention call.
 *
 * The `operator` modifier is inherited through overrides: `class Delegate : Base() { override fun
 * provideDelegate(...) }` stays the provider of the `operator` base even though the declaration
 * itself does not repeat the keyword, while an override of an ordinary method is not eligible and
 * the compiler reports `OPERATOR_MODIFIER_REQUIRED` for such a call. The modifier is therefore
 * checked on the declaration first and, for an override, on the declarations it overrides. A
 * library declaration exposes no PSI, so one whose modifier cannot be inspected is accepted, and
 * a callable whose declaring class cannot be resolved keeps the same conservative answer.
 *
 * @return `true` when the compiler can use this callable as an operator
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KaCallableSymbol.isOperatorEligible(): Boolean {
    val function = this as? KaFunctionSymbol ?: return true
    val modifierList = function.psi as? KtModifierListOwner ?: return true
    if (modifierList.hasModifier(KtTokens.OPERATOR_KEYWORD)) return true
    if (!modifierList.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false
    return runCatching { function.overridesOperator() }.getOrDefault(true)
}

/**
 * Tells whether this override overrides a declaration marked `operator`.
 *
 * A class reports only its own declaration under a member name — the inherited one is hidden by
 * the override — so the operator modifier of the base declaration has to be looked up in the
 * supertypes. All of them are searched, which covers a modifier inherited transitively: an override
 * of an override that also omits the keyword still finds the original operator declaration.
 *
 * The overridden declaration is the one whose signature matches, not merely any same-named and
 * same-arity one: a base class may declare both an `operator provideDelegate(Host, KProperty<*>)`
 * and an ordinary `provideDelegate(Any?, KProperty<*>)`, and an override of the ordinary one is
 * not an operator even though the operator overload exists in the same superclass. The base
 * declaration is compared as instantiated through the supertype entry, so `Base<T>` becomes the
 * argument the class actually passes.
 *
 * @return `true` when a supertype declares this callable's signature as an operator
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaFunctionSymbol.overridesOperator(): Boolean {
    val classId = callableId?.classId ?: return true
    val classSymbol = findClass(classId) ?: return true
    val name = (this as? KaNamedSymbol)?.name ?: return true
    return classSymbol.defaultType.allSupertypes.any { supertype ->
        val substitutor = supertype.classTypeSubstitutor()
        supertype.expandedSymbol?.memberScope?.callables(name)
            ?.filterIsInstance<KaFunctionSymbol>()
            ?.any { candidate ->
                candidate.declaresOperatorModifier() && overridesSignatureOf(candidate, substitutor)
            } == true
    }
}

/**
 * Tells whether this callable's value parameter list matches [candidate]'s.
 *
 * The types are compared as the supertype entry instantiates them, because that is the signature
 * the override is checked against. A spelling that still mentions a type parameter cannot be
 * compared without inferring it, so such a pair is treated as matching — the alternative would
 * reject overrides of library generic operators the plugin cannot inspect further.
 *
 * @param candidate declaration found in a supertype
 * @param substitutor instantiation of [candidate]'s declaring class through the supertype entry
 * @return `true` when the signatures match or cannot be distinguished
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaFunctionSymbol.overridesSignatureOf(
    candidate: KaFunctionSymbol,
    substitutor: KaSubstitutor?,
): Boolean {
    if (candidate.valueParameters.size != valueParameters.size) return false
    return valueParameters.indices.all { index ->
        val declaredType = candidate.valueParameters[index].returnType
        val theirType = substitutor?.let { current ->
            runCatching { current.substitute(declaredType) }.getOrDefault(declaredType)
        } ?: declaredType
        val myType = valueParameters[index].returnType
        if (myType.mentionsTypeParameter() || theirType.mentionsTypeParameter()) return@all true
        runCatching { myType.semanticallyEquals(theirType) }.getOrDefault(true)
    }
}

/**
 * Tells whether this declaration itself carries the `operator` modifier.
 *
 * A declaration without PSI cannot be inspected and is treated as an operator, matching the
 * conservative answer [isOperatorEligible] gives to declarations the plugin cannot see.
 *
 * @return `true` when the modifier is present or cannot be inspected
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
private fun KaFunctionSymbol.declaresOperatorModifier(): Boolean {
    val modifierList = psi as? KtModifierListOwner ?: return true
    return modifierList.hasModifier(KtTokens.OPERATOR_KEYWORD)
}

/** Class id of `kotlin.reflect.KProperty`, the property parameter every accessor declares. */
private val K_PROPERTY_CLASS_ID = ClassId.topLevel(FqName("kotlin.reflect.KProperty"))
