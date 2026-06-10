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
package io.github.nbplugins.kotlin.nbm.formatting.options

import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import utils.KotlinTestCase

/**
 * Verifies that per-project [CodeStyleSettings] surfaced via
 * [ProjectCodeStyleStorage.getSettings] drive the format output when pushed
 * through [KotlinFormatterUtils.pushSettings] — the path used by the real
 * format entry point.
 */
class ProjectCodeStyleStorageFormatTest : KotlinTestCase("ProjectCodeStyleStorageFormatTest", "formatting") {

    private val source = "fun f() {\nval x = 1\n}\n"

    override fun tearDown() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        super.tearDown()
    }

    private fun perProjectSettings(indentSize: Int): CodeStyleSettings {
        val s = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(s)
        s.indentOptions.INDENT_SIZE = indentSize
        return s
    }

    /** Per-project INDENT_SIZE = 3 drives the format output. */
    fun testPerProjectIndentDrivesFormatting() {
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, perProjectSettings(3))
        KotlinFormatterUtils.pushSettings(ProjectCodeStyleStorage.getSettings(project))
        try {
            val formatted = KotlinFormatterUtils.formatCode(source, "f.kt", project, "\n")
            assertTrue(
                "body must be indented with 3 spaces from per-project settings, got:\n$formatted",
                formatted.contains("\n   val x = 1")
            )
            assertFalse(
                "body must not be indented with 4 spaces, got:\n$formatted",
                formatted.contains("\n    val x = 1")
            )
        } finally {
            KotlinFormatterUtils.popSettings()
            ProjectCodeStyleStorage.onProjectSettingsCleared(project)
        }
    }

    /** With no per-project override, [getSettings] returns the global singleton. */
    fun testNoOverrideFallsBackToGlobalSingleton() {
        ProjectCodeStyleStorage.onProjectSettingsCleared(project)
        val global = KotlinFormatterUtils.getSettings()
        assertSame(
            "without per-project override, getSettings should return the global singleton",
            global,
            ProjectCodeStyleStorage.getSettings(project)
        )
    }
}
