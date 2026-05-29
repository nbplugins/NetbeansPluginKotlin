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
package org.jetbrains.kotlin.idea.base.psi

import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiUtil

/**
 * Stub for `KtExpression.safeDeparenthesize()` from `base/psi`.
 * Delegates to [KtPsiUtil.safeDeparenthesize], which handles the case where the
 * expression is not a parenthesized expression by returning it unchanged.
 * Used by [OperatorToFunctionConverter].
 */
fun KtExpression.safeDeparenthesize(): KtExpression = KtPsiUtil.safeDeparenthesize(this)

/**
 * Stub for `KtPropertyAccessor.deleteBody()` from `base/psi`.
 * Deletes everything from the left parenthesis to the end of the accessor body.
 * Used by [org.jetbrains.kotlin.idea.codeinsight.utils.KotlinPsiUtils].
 */
fun KtPropertyAccessor.deleteBody() {
    val leftParen = leftParenthesis ?: return
    deleteChildRange(leftParen, lastChild)
}
