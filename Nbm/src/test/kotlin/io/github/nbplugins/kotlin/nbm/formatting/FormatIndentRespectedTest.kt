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
package io.github.nbplugins.kotlin.nbm.formatting

import com.intellij.psi.codeStyle.CodeStyleSettings
import utils.KotlinTestCase

/**
 * Regression tests for the bug where [KotlinFormatterUtils.buildModel] used to
 * overwrite pushed indent options with values read from a parallel preferences
 * store, silently ignoring the user's Tools → Options → Kotlin settings.
 */
class FormatIndentRespectedTest : KotlinTestCase("FormatIndentRespectedTest", "formatting") {

    /** Source snippet whose body must be indented at level 1 after formatting. */
    private val source = "fun f() {\nval x = 1\n}\n"

    /** Builds a fresh [CodeStyleSettings] with the Kotlin provider registered. */
    private fun freshSettings(): CodeStyleSettings {
        val s = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(s)
        return s
    }

    /** Pushed indent settings with INDENT_SIZE = 2 must produce 2-space body indent. */
    fun testIndentSizeTwoIsRespected() {
        val s = freshSettings().also { it.indentOptions.INDENT_SIZE = 2 }
        KotlinFormatterUtils.pushSettings(s)
        try {
            val formatted = KotlinFormatterUtils.formatCode(source, "f.kt", project, "\n")
            assertTrue(
                "body must be indented with 2 spaces, got:\n$formatted",
                formatted.contains("\n  val x = 1")
            )
            assertFalse(
                "body must not be indented with 4 spaces, got:\n$formatted",
                formatted.contains("\n    val x = 1")
            )
        } finally {
            KotlinFormatterUtils.popSettings()
        }
    }

    /** Pushed indent settings with INDENT_SIZE = 8 must produce 8-space body indent. */
    fun testIndentSizeEightIsRespected() {
        val s = freshSettings().also { it.indentOptions.INDENT_SIZE = 8 }
        KotlinFormatterUtils.pushSettings(s)
        try {
            val formatted = KotlinFormatterUtils.formatCode(source, "f.kt", project, "\n")
            assertTrue(
                "body must be indented with 8 spaces, got:\n$formatted",
                formatted.contains("\n        val x = 1")
            )
        } finally {
            KotlinFormatterUtils.popSettings()
        }
    }

    /** Pushed indent settings with USE_TAB_CHARACTER must produce a tab indent. */
    fun testUseTabCharacterIsRespected() {
        val s = freshSettings().also {
            it.indentOptions.USE_TAB_CHARACTER = true
            it.indentOptions.TAB_SIZE = 4
            it.indentOptions.INDENT_SIZE = 4
        }
        KotlinFormatterUtils.pushSettings(s)
        try {
            val formatted = KotlinFormatterUtils.formatCode(source, "f.kt", project, "\n")
            assertTrue(
                "body must be indented with a tab character, got:\n$formatted",
                formatted.contains("\n\tval x = 1")
            )
        } finally {
            KotlinFormatterUtils.popSettings()
        }
    }
}
