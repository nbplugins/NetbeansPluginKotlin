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
package io.github.nbplugins.kotlin.nbm.refactoring

/**
 * A Kotlin `import` directive found on the line that contains the editor caret.
 *
 * @param importPath dotted path written after `import`, without the `.*` suffix or an alias
 * @param allUnder whether the directive imports a whole package (`import a.b.*`)
 */
internal data class KotlinImportDirective(val importPath: String, val allUnder: Boolean)

/** Locates the Kotlin `import` directive on the source line containing a caret offset. */
internal object KotlinImportDirectiveParser {

    /**
     * Parses the line that contains [caretOffset] as an `import` directive.
     *
     * The check works on document text rather than PSI so that unsaved edits are honoured and a
     * menu action can decide its target without bootstrapping a K2 analysis session.
     *
     * @param text current editor document text
     * @param caretOffset caret position inside [text]
     * @return the parsed directive, or `null` when the caret is not on an import line
     */
    fun findAt(text: CharSequence, caretOffset: Int): KotlinImportDirective? {
        if (caretOffset < 0 || caretOffset > text.length) return null
        val line = text.subSequence(lineStart(text, caretOffset), lineEnd(text, caretOffset)).toString()
        val match = IMPORT_DIRECTIVE.matchEntire(line) ?: return null
        return KotlinImportDirective(match.groupValues[1], match.groupValues[2].isNotEmpty())
    }

    /** Returns the first character of the line that owns [offset], treating the caret as left-biased. */
    private fun lineStart(text: CharSequence, offset: Int): Int {
        var index = offset - 1
        while (index >= 0 && text[index] != '\n') index--
        return index + 1
    }

    /** Returns the end of the line that owns [offset], excluding a trailing newline. */
    private fun lineEnd(text: CharSequence, offset: Int): Int {
        var index = offset
        while (index < text.length && text[index] != '\n') index++
        return index
    }

    /** `import` + dotted path + optional `.*` + optional alias; comments never match. */
    private val IMPORT_DIRECTIVE = Regex(
        "^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)(\\.\\*)?(?:\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*$",
    )
}
