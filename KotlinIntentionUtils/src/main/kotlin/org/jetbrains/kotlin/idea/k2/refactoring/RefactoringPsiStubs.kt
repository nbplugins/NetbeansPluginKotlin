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
package org.jetbrains.kotlin.idea.k2.refactoring

import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * Stub for `KtLambdaExpression.moveFunctionLiteralOutsideParenthesesIfPossible()`.
 *
 * The full implementation moves a trailing lambda argument from inside the parentheses
 * to outside (e.g., `foo({ x -> ... })` → `foo { x -> ... }`). This is a code-style
 * optimisation, not a semantic change.
 *
 * In standalone (NetBeans) mode the PSI mutation utilities required for this transform
 * are not available, so this stub is a no-op. The produced code is semantically correct
 * but may keep the lambda inside the parentheses.
 */
fun KtLambdaExpression.moveFunctionLiteralOutsideParenthesesIfPossible() {
    // No-op stub: trailing-lambda style optimisation is not available in standalone mode.
    // The generated code remains correct; only the parentheses style may differ.
}
