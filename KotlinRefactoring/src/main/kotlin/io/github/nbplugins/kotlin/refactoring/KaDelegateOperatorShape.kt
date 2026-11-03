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
import org.jetbrains.kotlin.analysis.api.components.expandedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Tells whether this callable has the call shape Kotlin requires of a delegate operator.
 *
 * Delegated properties select their operators by name, but a same-named member is only an
 * accessor when its signature fits the delegation protocol: `provideDelegate` and `getValue` take
 * the delegating receiver and the property, while `setValue` adds the assigned value. A
 * parameterless `fun getValue()` is therefore an ordinary member and must not hide the extension
 * the property actually uses.
 *
 * @param operatorName `provideDelegate`, `getValue`, or `setValue`
 * @return `true` when this callable can be selected as that delegate operator
 */
context(_: KaSession)
internal fun KaCallableSymbol.isDelegateOperatorShape(operatorName: String): Boolean {
    val function = this as? KaFunctionSymbol ?: return false
    val expectedParameters = if (operatorName == "setValue") 3 else 2
    if (function.valueParameters.size != expectedParameters) return false
    return runCatching {
        function.valueParameters[1].returnType.expandedSymbol?.classId == K_PROPERTY_CLASS_ID
    }.getOrDefault(false)
}

/** Class id of `kotlin.reflect.KProperty`, the property parameter every accessor declares. */
private val K_PROPERTY_CLASS_ID = ClassId.topLevel(FqName("kotlin.reflect.KProperty"))
