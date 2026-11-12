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
import org.jetbrains.kotlin.analysis.api.components.defaultTypeWithStarProjections
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.findClass
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtModifierListOwner

/**
 * Tells whether this callable has the call shape Kotlin requires of a delegate operator.
 *
 * Delegated properties select their operators by name, but a same-named method takes part only
 * when it is declared `operator` *and* its signature fits the delegation protocol:
 * `provideDelegate` and `getValue` take the delegating receiver and the property, while `setValue`
 * adds the assigned value. An ordinary `fun getValue()` is therefore ignored, and so is a
 * non-operator `fun provideDelegate(...)` that only happens to match the parameter shape — Kotlin
 * never calls such a method, so treating it as a provider would switch the accessor lookup to its
 * result type.
 *
 * Kotlin passes a `KProperty` instance to the property parameter, which may be declared with any
 * supertype of it (`Any?`, for example), so the parameter is matched by assignability rather than
 * by its classifier. Library declarations expose no PSI, so only a declaration that visibly lacks
 * the modifier is rejected.
 *
 * @param operatorName `provideDelegate`, `getValue`, or `setValue`
 * @return `true` when this callable can be selected as that delegate operator
 */
@OptIn(KaExperimentalApi::class)
context(_: KaSession)
internal fun KaCallableSymbol.isDelegateOperatorShape(operatorName: String): Boolean {
    val function = this as? KaFunctionSymbol ?: return false
    val modifierList = function.psi as? KtModifierListOwner
    if (modifierList != null && !modifierList.hasModifier(KtTokens.OPERATOR_KEYWORD)) return false
    val expectedParameters = if (operatorName == "setValue") 3 else 2
    if (function.valueParameters.size != expectedParameters) return false
    return runCatching {
        val propertyType = findClass(K_PROPERTY_CLASS_ID)?.defaultTypeWithStarProjections ?: return@runCatching false
        propertyType.isSubtypeOf(function.valueParameters[1].returnType)
    }.getOrDefault(false)
}

/** Class id of `kotlin.reflect.KProperty`, the property parameter every accessor declares. */
private val K_PROPERTY_CLASS_ID = ClassId.topLevel(FqName("kotlin.reflect.KProperty"))
