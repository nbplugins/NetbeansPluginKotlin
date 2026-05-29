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
package org.jetbrains.kotlin.idea.util

import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.lang.BinaryOperationPrecedence
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtOperationExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression

/**
 * Standalone replacement for the upstream `PsiPrecedences` (from `base/code-insight`).
 *
 * The upstream version depends on `KotlinExpressionParsing.Precedence` which was removed in
 * Kotlin 2.3.21. This version uses the public `BinaryOperationPrecedence` enum and its ordinal
 * ordering (lower ordinal = tighter binding, matching the reversed convention in the original).
 *
 * PREFIX / POSTFIX sentinels are kept at -1 / -2 to preserve the original semantics where
 * prefix and postfix expressions are considered tighter than any binary operator.
 */
object PsiPrecedences {
    private val PRECEDENCE_OF_ATOMIC_EXPRESSION: Int = Int.MAX_VALUE
    private val PRECEDENCE_OF_PREFIX_EXPRESSION: Int = -1
    private val PRECEDENCE_OF_POSTFIX_EXPRESSION: Int = -2

    private val precedence: Map<IElementType, Int> by lazy {
        val map = HashMap<IElementType, Int>()
        // BinaryOperationPrecedence entries are ordered from tightest (AS=0) to loosest (ASSIGNMENT=last).
        for ((i, entry) in BinaryOperationPrecedence.values().withIndex()) {
            for (token in entry.tokens) {
                map[token] = i
            }
        }
        map
    }

    fun getPrecedence(expression: KtExpression): Int = when (expression) {
        is KtAnnotatedExpression,
        is KtLabeledExpression,
        is KtPrefixExpression -> PRECEDENCE_OF_PREFIX_EXPRESSION
        is KtPostfixExpression -> PRECEDENCE_OF_POSTFIX_EXPRESSION
        is KtOperationExpression -> {
            val op = expression.operationReference.getReferencedNameElementType()
            precedence[op] ?: PRECEDENCE_OF_ATOMIC_EXPRESSION
        }
        else -> PRECEDENCE_OF_ATOMIC_EXPRESSION
    }

    /** Returns true when [subject] binds tighter (has higher priority) than [tighterThan]. */
    fun isTighter(subject: Int, tighterThan: Int): Boolean = subject < tighterThan
}
