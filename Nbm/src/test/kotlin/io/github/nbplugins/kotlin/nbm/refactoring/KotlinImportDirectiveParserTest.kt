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

import org.netbeans.junit.NbTestCase

/** Unit tests for [KotlinImportDirectiveParser]. */
class KotlinImportDirectiveParserTest : NbTestCase("KotlinImportDirectiveParserTest") {

    /** Verifies a caret inside a plain import resolves the directive that contains it. */
    fun testFindAt_returnsDirectiveUnderCaret() {
        val text = "package sample\n\nimport other.pkg.Widget\n\nfun use() = Widget()\n"
        val caret = text.indexOf("pkg") + 1

        val directive = KotlinImportDirectiveParser.findAt(text, caret)

        assertEquals("other.pkg.Widget", directive?.importPath)
        assertEquals(false, directive?.allUnder)
    }

    /** Verifies a star import is reported as importing a package rather than a declaration. */
    fun testFindAt_marksStarImport() {
        val text = "package sample\n\nimport other.pkg.*\n"
        val caret = text.indexOf("other")

        val directive = KotlinImportDirectiveParser.findAt(text, caret)

        assertEquals("other.pkg", directive?.importPath)
        assertEquals(true, directive?.allUnder)
    }

    /** Verifies an alias suffix does not become part of the imported path. */
    fun testFindAt_ignoresAliasSuffix() {
        val text = "package sample\n\nimport other.pkg.Widget as W\n"
        val caret = text.indexOf("as W") + 1

        val directive = KotlinImportDirectiveParser.findAt(text, caret)

        assertEquals("other.pkg.Widget", directive?.importPath)
        assertEquals(false, directive?.allUnder)
    }

    /** Verifies the caret placed right after the directive still targets that directive. */
    fun testFindAt_acceptsCaretAtDirectiveEnd() {
        val text = "import other.pkg.Widget\npackage sample\n"
        val caret = text.indexOf('\n')

        val directive = KotlinImportDirectiveParser.findAt(text, caret)

        assertEquals("other.pkg.Widget", directive?.importPath)
    }

    /** Verifies a caret on an ordinary source line does not resolve any import. */
    fun testFindAt_rejectsNonImportLine() {
        val text = "package sample\n\nfun use() = 1\n"
        val caret = text.indexOf("use")

        assertNull(KotlinImportDirectiveParser.findAt(text, caret))
    }

    /** Verifies a commented-out import is not treated as an active directive. */
    fun testFindAt_rejectsCommentLine() {
        val text = "// import other.pkg.Widget\n"
        val caret = text.indexOf("other")

        assertNull(KotlinImportDirectiveParser.findAt(text, caret))
    }

    /** Verifies the package directive of the same file is not mistaken for an import. */
    fun testFindAt_rejectsPackageDirective() {
        val text = "package sample\n"
        val caret = text.indexOf("sample")

        assertNull(KotlinImportDirectiveParser.findAt(text, caret))
    }

    /** Verifies offsets outside the supplied text are rejected instead of throwing. */
    fun testFindAt_rejectsOutOfRangeOffset() {
        val text = "import other.pkg.Widget\n"

        assertNull(KotlinImportDirectiveParser.findAt(text, -1))
        assertNull(KotlinImportDirectiveParser.findAt(text, text.length + 1))
    }
}
