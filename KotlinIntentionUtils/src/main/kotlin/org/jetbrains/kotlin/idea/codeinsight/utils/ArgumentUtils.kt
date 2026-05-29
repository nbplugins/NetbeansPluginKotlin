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
package org.jetbrains.kotlin.idea.codeinsight.utils

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Dereferences all [SmartPsiElementPointer] keys in this map, discarding entries whose pointers
 * can no longer be resolved.
 * Mirrors `GeneralPsiUtils.dereferenceValidKeys` from `code-insight/utils`.
 */
fun <E : PsiElement, V> Map<SmartPsiElementPointer<E>, V>.dereferenceValidKeys(): Map<E, V> {
    val result = LinkedHashMap<E, V>()
    forEach { (pointer, value) -> pointer.element?.let { result[it] = value } }
    return result
}

/**
 * Returns the source text of [argument] with its argument name stripped, handling vararg and
 * `arrayOf` spread expansion. Returns a single-element list in most cases; multiple elements
 * when a named `arrayOf(…)` vararg is expanded into its individual items.
 *
 * Returns raw text strings rather than new PSI elements to avoid `ChangeUtil.copyElement`,
 * which requires the `com.intellij.treeCopyHandler` extension point unavailable in standalone
 * analysis mode.
 *
 * Mirrors `KotlinPsiUtils.createArgumentWithoutName` from `code-insight/utils`.
 *
 * @param argument the argument whose name should be removed
 * @param isVararg `true` when this argument corresponds to a vararg parameter
 * @param isArrayOf `true` when the argument expression is an `arrayOf(…)` call
 * @return a list of argument text strings without the name prefix
 */
fun createArgumentWithoutName(
    argument: KtValueArgument,
    isVararg: Boolean = false,
    isArrayOf: Boolean = false,
): List<String> {
    if (!argument.isNamed()) return listOf(argument.text)
    val argumentExpr = argument.getArgumentExpression() ?: return emptyList()
    return when {
        isVararg && argumentExpr is KtCollectionLiteralExpression ->
            argumentExpr.getInnerExpressions().map { it.text }
        isVararg && argumentExpr is KtCallExpression && isArrayOf ->
            argumentExpr.valueArguments.map { it.getArgumentExpression()?.text ?: it.text }
        else -> {
            val prefix = if (argument.getSpreadElement() != null) "*" else ""
            listOf("$prefix${argumentExpr.text}")
        }
    }
}
