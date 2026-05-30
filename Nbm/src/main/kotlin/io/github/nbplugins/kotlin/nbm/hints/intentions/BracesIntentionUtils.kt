/*******************************************************************************
 * Copyright 2000-2022 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Navigates from [ifExpr] to the outermost `if` in an else-if chain.
 *
 * For `if (a) ... else if (b) ... else ...`, returns the root `if` regardless of which
 * nested `else if` [ifExpr] is.
 */
internal fun outermostIf(ifExpr: KtIfExpression): KtIfExpression {
    var cur = ifExpr
    while (true) {
        val outer = cur.parent?.parent as? KtIfExpression ?: break
        // cur must be the else-branch of outer, not the then-branch
        if (outer.`else` !== cur) break
        cur = outer
    }
    return cur
}

/**
 * Collects all branch expressions from an if-else chain or when-expression.
 *
 * For `if (a) X else if (b) Y else Z` returns `[X, Y, Z]`.
 * For a `when` expression returns the expression of each entry.
 *
 * @param expr a [KtIfExpression] or [KtWhenExpression]
 * @return list of branch [KtExpression]s (may include [KtBlockExpression]s)
 */
internal fun allBranchExpressions(expr: KtExpression): List<KtExpression> = when (expr) {
    is KtIfExpression -> buildList {
        fun collect(e: KtIfExpression) {
            e.then?.let { add(it) }
            when (val el = e.`else`) {
                is KtIfExpression -> collect(el)
                else -> el?.let { add(it) }
            }
        }
        collect(expr)
    }
    is KtWhenExpression -> expr.entries.mapNotNull { it.expression }
    else -> emptyList()
}

/**
 * Wraps [exprText] in a properly-indented block `{ ... }`.
 *
 * The indentation of the line containing [exprStartOffset] in [docText] is used as the base
 * indent; the statement gets one extra level (4 spaces). This matches IDEA's behaviour when
 * "Add braces" is invoked.
 *
 * @param exprText   text of the expression to wrap
 * @param exprStartOffset  start offset of the expression in [docText]
 * @param docText    full document text (read before any modifications)
 * @return multi-line block string, e.g. `"{\n    doSomething()\n}"`
 */
internal fun makeBlock(exprText: String, exprStartOffset: Int, docText: String): String {
    var lineStart = exprStartOffset
    while (lineStart > 0 && docText[lineStart - 1] != '\n') lineStart--
    val lineIndent = docText.substring(lineStart, exprStartOffset).takeWhile { it == ' ' || it == '\t' }
    val step = detectIndentStep(docText, exprStartOffset)
    return "{\n$lineIndent$step$exprText\n$lineIndent}"
}
