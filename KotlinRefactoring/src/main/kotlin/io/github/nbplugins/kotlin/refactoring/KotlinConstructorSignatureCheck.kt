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

/**
 * Decides whether a top-level function would collide with a class's primary constructor.
 *
 * Renaming a class writes its new name verbatim, so a same-package function whose call signature
 * matches the constructor becomes a conflicting overload that Kotlin rejects. Parameter types are
 * compared as resolved types, because `Int` and a fully qualified `kotlin.Int` are the same Kotlin
 * type even though their source spellings differ.
 */
object KotlinConstructorSignatureCheck {

    /**
     * Tells whether [function] has the same call signature as the primary constructor of [primaryClass].
     *
     * An extension function has an implicit receiver and therefore cannot conflict with a
     * constructor. A parameter whose type cannot be resolved makes the comparison inconclusive,
     * which is reported as a conflict so the rename is rejected instead of producing uncompilable
     * code.
     *
     * @param primaryClass class whose constructor the rename would introduce
     * @param function same-package function that would share the class's new name
     * @return `true` when a call could not tell the constructor and the function apart
     */
    fun conflictsWithPrimaryConstructor(primaryClass: KtClass, function: KtNamedFunction): Boolean {
        if (function.receiverTypeReference != null) return false
        val constructorParameters = primaryClass.primaryConstructorParameters
        if (function.valueParameters.size != constructorParameters.size) return false
        return analyze(function) {
            constructorParameters.indices.all { index ->
                val constructorType = runCatching { constructorParameters[index].typeReference?.type }.getOrNull()
                val functionType = runCatching { function.valueParameters[index].typeReference?.type }.getOrNull()
                constructorType == null || functionType == null || functionType.semanticallyEquals(constructorType)
            }
        }
    }
}
