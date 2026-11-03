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

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.components.type
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Decides whether a top-level function would collide with a constructor of a renamed class.
 *
 * Renaming a class writes its new name verbatim, so a same-package function whose call signature
 * matches a constructor becomes a conflicting overload that Kotlin rejects. Parameter types are
 * compared as resolved types, because `Int` and a fully qualified `kotlin.Int` are the same Kotlin
 * type even though their source spellings differ.
 */
object KotlinConstructorSignatureCheck {

    /**
     * Tells whether [function] has the same call signature as a constructor of [primaryClass].
     *
     * Every constructor counts, not only the primary one: a class that declares only secondary
     * constructors has no primary constructor at all, while a class that declares none gets an
     * implicit no-argument primary constructor. An extension function has an implicit receiver and
     * therefore cannot conflict with a constructor. A parameter whose type cannot be resolved makes
     * the comparison inconclusive, which is reported as a conflict so the rename is rejected
     * instead of producing uncompilable code.
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
                parameters.indices.all { index ->
                    val constructorType = runCatching { parameters[index].typeReference?.type }.getOrNull()
                    val functionType = runCatching { function.valueParameters[index].typeReference?.type }.getOrNull()
                    constructorType == null || functionType == null || functionType.semanticallyEquals(constructorType)
                }
            }
        }
    }

    /**
     * Returns the parameter list of every constructor the renamed class would expose.
     *
     * A class with a primary constructor uses its parameters; one that declares only secondary
     * constructors has no primary constructor, and one that declares none gets the implicit
     * no-argument constructor.
     *
     * @param classOrObject class whose constructors are being compared
     * @return one parameter list per constructor
     */
    private fun constructorSignatures(classOrObject: KtClass): List<List<KtParameter>> {
        val signatures = mutableListOf<List<KtParameter>>()
        val primaryConstructor = classOrObject.primaryConstructor
        when {
            primaryConstructor != null -> signatures += primaryConstructor.valueParameters
            classOrObject.secondaryConstructors.isEmpty() -> signatures.add(emptyList())
        }
        classOrObject.secondaryConstructors.forEach { constructor -> signatures.add(constructor.valueParameters) }
        return signatures
    }
}
