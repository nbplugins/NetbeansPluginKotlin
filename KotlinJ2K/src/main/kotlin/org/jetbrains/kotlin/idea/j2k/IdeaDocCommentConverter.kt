/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
package org.jetbrains.kotlin.idea.j2k

import com.intellij.psi.javadoc.PsiDocComment

/**
 * Standalone replacement for IDEA's `IdeaDocCommentConverter`
 * (`plugins/kotlin/frontend-independent/src/org/jetbrains/kotlin/idea/j2k/IdeaDocCommentConverter.kt`).
 *
 * IDEA's version builds a temporary HTML file from the Javadoc PSI and converts it to KDoc with an
 * `HtmlToMarkdownConverter` visitor. That path needs the platform **HTML/XML PSI** (`HtmlFileType`,
 * `com.intellij.psi.html`, `com.intellij.psi.xml`), which the plugin does not bundle, so this
 * version works on the plain Javadoc text instead:
 *
 * - the Javadoc open/close delimiters and the leading asterisk of every line are stripped;
 * - `@deprecated` is dropped (KDoc has no such tag; the caller emits the Kotlin annotation);
 * - inline `{@link X}` / `{@code x}` / `{@literal x}` are rewritten to `[X]` / `` `x` `` / `x`;
 * - everything else (descriptions, `@param`, `@return`, `@throws`, `@see`) is kept verbatim.
 *
 * Documented limitation: HTML formatting inside a Javadoc comment (<p>, <ul>, <code>, …) is kept as
 * literal text instead of being converted to Markdown.
 */
object IdeaDocCommentConverter : DocCommentConverter {

    private val inlineTagRegex = Regex("\\{@(link|linkplain|code|literal)\\s+([^}]*)}")
    private val leadingAsteriskRegex = Regex("^\\s*\\*+ ?")

    /**
     * Converts a Javadoc comment into a KDoc comment.
     *
     * @param docComment the Javadoc comment to convert.
     * @return the KDoc comment text, or an empty string when the comment carries no content
     *         that KDoc can represent.
     */
    override fun convertDocComment(docComment: PsiDocComment): String {
        val body = docComment.text
            .removePrefix("/**")
            .removeSuffix("*/")

        val lines = body.lines()
            .map { leadingAsteriskRegex.replace(it, "") }
            .map { stripInlineTags(it) }
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }

        val withoutDeprecated = lines.filterNot { it.trimStart().startsWith("@deprecated") }
            .dropLastWhile { it.isBlank() }
        if (withoutDeprecated.none { it.isNotBlank() }) return ""

        return buildString {
            append("/**")
            for (line in withoutDeprecated) {
                append("\n *")
                if (line.isNotBlank()) append(' ').append(line)
            }
            append("\n */")
        }
    }

    /**
     * Rewrites Javadoc inline tags to their KDoc equivalent.
     *
     * @param line one Javadoc line with its leading asterisk already removed.
     * @return the same line with `{@link …}`, `{@code …}` and `{@literal …}` replaced.
     */
    private fun stripInlineTags(line: String): String = inlineTagRegex.replace(line) { match ->
        val kind = match.groupValues[1]
        val content = match.groupValues[2].trim()
        when (kind) {
            "code" -> if (content.startsWith('`')) content else "`$content`"
            "link", "linkplain" -> {
                val target = content.substringBefore(' ')
                val label = content.substringAfter(' ', "").ifBlank { target }
                if (label == target) "[${asKdocReference(target)}]"
                else "[$label][${asKdocReference(target)}]"
            }
            else -> content
        }
    }

    /**
     * Converts a Javadoc reference (`Type#member(params)`) to a KDoc reference (`Type.member`).
     *
     * @param reference the Javadoc link target.
     * @return the KDoc link target.
     */
    private fun asKdocReference(reference: String): String =
        reference.substringBefore('(').replace('#', '.').removeSuffix(".")
}
