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
package io.github.nbplugins.kotlin.refactoring

import org.jetbrains.kotlin.name.FqName

/**
 * Renders Kotlin names as source text with the escaping the language requires.
 *
 * [FqName.asString] and raw declaration names join unescaped segments, which is not valid source
 * for identifiers that need backticks (hard keywords or names with spaces). Generated references,
 * imports, and package directives must use these helpers instead.
 */
object KotlinSourceNames {

    /**
     * Kotlin hard keywords, which cannot be written as bare identifiers in generated source.
     *
     * Soft keywords (`value`, `field`, `import`, ...) are legal identifiers and stay plain.
     */
    val HARD_KEYWORDS: Set<String> = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    /**
     * Tells whether an unescaped name can be written without backticks.
     *
     * Names made of underscores only (`_`, `__`, ...) are reserved in Kotlin and need backticks
     * just like hard keywords, so they are not plain.
     *
     * @param name identifier as stored by Kotlin PSI
     * @return `true` for a plain letter/underscore-led non-keyword identifier
     */
    fun isPlainIdentifier(name: String): Boolean =
        name.isNotEmpty() &&
            (name.first().isLetter() || name.first() == '_') &&
            name.all { symbol -> symbol.isLetterOrDigit() || symbol == '_' } &&
            name.any { symbol -> symbol != '_' } &&
            name !in HARD_KEYWORDS

    /**
     * Renders one identifier as Kotlin source, adding backticks when the bare spelling is invalid.
     *
     * @param name unescaped identifier as stored by Kotlin PSI
     * @return the name unchanged when it is plain, otherwise backticked
     */
    fun renderIdentifier(name: String): String = if (isPlainIdentifier(name)) name else "`$name`"

    /**
     * Renders a fully qualified name as Kotlin source text.
     *
     * @param fqName fully qualified name of a declaration or a package
     * @return the FQN with every segment escaped when it cannot be written bare
     */
    fun renderFqName(fqName: FqName): String =
        fqName.pathSegments().joinToString(separator = ".") { segment -> renderIdentifier(segment.asString()) }
}
