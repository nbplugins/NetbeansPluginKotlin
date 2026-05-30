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
package org.jetbrains.kotlin.util

import kotlin.reflect.KClass

/**
 * Standalone stub for `match` from `base/util/CollectionUtils.kt` (upstream).
 *
 * Checks that the first N elements of this sequence are instances of [expectedTypes] (in order)
 * and that element N is an instance of [last]. Returns element N cast to [T], or null if any
 * type check fails or the sequence is too short.
 *
 * Used by [org.jetbrains.kotlin.idea.codeinsight.utils.DemorgansLawUtils].
 */
fun <T : Any> Sequence<Any>.match(vararg expectedTypes: KClass<*>, last: KClass<T>): T? {
    val allTypes = expectedTypes.toList() + last
    val iter = iterator()
    var result: Any? = null
    for (expectedType in allTypes) {
        if (!iter.hasNext()) return null
        val element = iter.next()
        if (!expectedType.isInstance(element)) return null
        result = element
    }
    @Suppress("UNCHECKED_CAST")
    return result as? T
}
