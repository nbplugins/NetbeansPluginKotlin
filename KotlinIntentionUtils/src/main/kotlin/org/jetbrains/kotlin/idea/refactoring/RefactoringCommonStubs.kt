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
package org.jetbrains.kotlin.idea.refactoring

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.unpackFunctionLiteral

/**
 * Stub for `KtCallExpression.getLastLambdaExpression()`.
 *
 * In the full IDE implementation this returns the last lambda argument from the value
 * argument list (not trailing lambda arguments). Used by
 * [org.jetbrains.kotlin.idea.k2.refactoring.util.ConvertReferenceToLambdaUtil] to
 * trigger trailing-lambda style optimisation after conversion.
 *
 * This simplified version returns `null` when there are already trailing lambda arguments
 * (the common post-conversion case), and otherwise returns the last value argument's
 * lambda expression if present.
 *
 * @receiver the call expression to inspect
 * @return last value-argument lambda, or null if there are trailing lambda args or none exists
 */
fun KtCallExpression.getLastLambdaExpression(): KtLambdaExpression? {
    if (lambdaArguments.isNotEmpty()) return null
    return valueArguments.lastOrNull()?.getArgumentExpression()?.unpackFunctionLiteral()
}
