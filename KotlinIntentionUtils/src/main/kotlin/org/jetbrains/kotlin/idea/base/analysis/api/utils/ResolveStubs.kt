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
package org.jetbrains.kotlin.idea.base.analysis.api.utils

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallElement

/**
 * Returns `true` when [callElement] is a call to one of the `arrayOf`, `intArrayOf`, etc. stdlib functions.
 * Simplified implementation: resolves by matching the callee name against the known set, which is
 * sufficient for the [RemoveArgumentNamesUtils] use case (named arguments are not allowed in
 * spread-operator array-of calls).
 */
private val ARRAY_OF_NAMES: Set<Name> = setOf(
    Name.identifier("arrayOf"),
    Name.identifier("byteArrayOf"), Name.identifier("shortArrayOf"),
    Name.identifier("intArrayOf"), Name.identifier("longArrayOf"),
    Name.identifier("floatArrayOf"), Name.identifier("doubleArrayOf"),
    Name.identifier("charArrayOf"), Name.identifier("booleanArrayOf"),
    Name.identifier("emptyArray"), Name.identifier("arrayOfNulls"),
    StandardNames.FqNames.array.shortName(),
)

context(_: KaSession)
fun isArrayOfCall(callElement: KtCallElement): Boolean {
    val calleeName = callElement.calleeExpression?.text ?: return false
    return Name.isValidIdentifier(calleeName) && Name.identifier(calleeName) in ARRAY_OF_NAMES
}
