/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package org.jetbrains.kotlin.idea.base.codeInsight

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaUsualClassType

/**
 * Minimal stub for [KotlinNameSuggester].
 *
 * The full implementation depends on IntelliJ Platform UI APIs not available in
 * standalone (NetBeans) mode. This stub provides only the methods needed by
 * [org.jetbrains.kotlin.idea.k2.refactoring.util.ConvertReferenceToLambdaUtil] and
 * [org.jetbrains.kotlin.idea.k2.codeinsight.intentions.ConvertForEachToForLoopIntention].
 */
class KotlinNameSuggester {
    /**
     * Suggests parameter names for a given type.
     * Returns a short lowercase name derived from the class name, or "it"/"fn" as fallback.
     *
     * @param type the type for which to suggest names
     * @return a lazy sequence of candidate names, starting with the best suggestion
     */
    context(_: KaSession)
    fun suggestTypeNames(type: KaType): Sequence<String> = sequence {
        val className = when (type) {
            is KaUsualClassType -> type.classId.shortClassName.asString()
                .replaceFirstChar { it.lowercaseChar() }
            is KaFunctionType -> "fn"
            else -> "it"
        }
        yield(className.ifEmpty { "it" })
    }

    companion object {
        /**
         * Suggests a name based on [name], ensuring it passes [validator].
         * If [name] is rejected, appends incrementing numeric suffixes until accepted.
         *
         * @param name the preferred base name
         * @param validator returns true if the candidate name is acceptable
         * @return the first accepted candidate name
         */
        fun suggestNameByName(name: String, validator: (String) -> Boolean): String {
            if (validator(name)) return name
            var index = 0
            while (true) {
                val candidate = "$name${++index}"
                if (validator(candidate)) return candidate
            }
        }
    }
}
