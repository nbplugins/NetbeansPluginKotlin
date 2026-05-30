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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.openapi.util.text.StringUtilRt
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

private const val TRIPLE_DOUBLE_QUOTE = "\"\"\""

/**
 * Converts an escaped `KtStringTemplateExpression` to a raw triple-quoted string literal.
 *
 * Returns the triple-quoted string `"""..."""` where escape sequences are unescaped and
 * lone `$` signs that would start a template entry are escaped via `${"$"}`.
 *
 * @receiver the string template expression to convert (must satisfy [canBeConvertedToStringLiteral])
 * @return the raw string literal text, ready to be inserted into the document
 */
internal fun convertToRaw(element: KtStringTemplateExpression): String {
    val content = buildString {
        val entries = element.entries
        for ((index, entry) in entries.withIndex()) {
            val value = if (entry is KtEscapeStringTemplateEntry) entry.unescapedValue else entry.text
            if (value.endsWith("$") && index < entries.size - 1) {
                val nextChar = entries[index + 1].let {
                    if (it is KtEscapeStringTemplateEntry) it.unescapedValue else it.text
                }.firstOrNull()
                if (nextChar?.isJavaIdentifierStart() == true || nextChar == '{') {
                    append("\${\"$\"}")
                    continue
                }
            }
            append(value)
        }
    }
    val normalised = StringUtilRt.convertLineSeparators(content, "\n")
    return "$TRIPLE_DOUBLE_QUOTE$normalised$TRIPLE_DOUBLE_QUOTE"
}

/**
 * Recursively collects all leaf operands of a binary `+` expression left-to-right.
 *
 * @receiver the root binary expression
 * @return operands in left-to-right order
 */
internal fun collectBinaryOperands(expr: org.jetbrains.kotlin.psi.KtBinaryExpression): List<org.jetbrains.kotlin.psi.KtExpression> {
    val result = mutableListOf<org.jetbrains.kotlin.psi.KtExpression>()
    fun collect(e: org.jetbrains.kotlin.psi.KtExpression?) {
        when (e) {
            is org.jetbrains.kotlin.psi.KtBinaryExpression -> { collect(e.left); collect(e.right) }
            is org.jetbrains.kotlin.psi.KtExpression -> result.add(e)
            else -> {}
        }
    }
    collect(expr)
    return result
}
