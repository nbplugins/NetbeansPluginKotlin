/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.options.formatter

import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import utils.KotlinTestCase
import java.util.prefs.Preferences

/**
 * Verifies that the [KotlinFormattingPreviewPane] renders byte-identical
 * formatted text regardless of the **Use tab character** checkbox value.
 *
 * <p>The UX intent is that toggling the checkbox only changes whether the
 * indent guides are drawn — the actual formatted text in the preview must
 * stay visually constant. This is enforced by [KotlinFormattingPreviewPane]
 * forcing {@code USE_TAB_CHARACTER = false} on the temporary settings before
 * calling the formatter.
 */
class KotlinFormatterPreviewInvarianceTest : KotlinTestCase(
    "KotlinFormatterPreviewInvarianceTest", "formatting"
) {

    /**
     * Same Tab Size, same Indent Size, only Use tab character flipped — the
     * preview text must match byte-for-byte.
     */
    fun testPreviewTextDoesNotChangeOnTabToggle() {
        val withTabs = renderPreview(useTab = true)
        val withSpaces = renderPreview(useTab = false)
        assertEquals(
            "preview text must not change when toggling Use tab character",
            withSpaces, withTabs
        )
    }

    private fun renderPreview(useTab: Boolean): String {
        val pane = KotlinFormattingPreviewPane(
            collectSettings = { prefs -> writeIndentOptions(prefs, tab = 4, indent = 2, useTab = useTab) },
            projectProvider = { project }
        )
        pane.refreshNow()
        return pane.getText()
    }

    private fun writeIndentOptions(prefs: Preferences, tab: Int, indent: Int, useTab: Boolean) {
        val tmp = CodeStyleSettings()
        val opts = tmp.indentOptions
        opts.TAB_SIZE = tab
        opts.INDENT_SIZE = indent
        opts.CONTINUATION_INDENT_SIZE = indent * 2
        opts.USE_TAB_CHARACTER = useTab
        KotlinCodeStylePreferences.save(tmp, prefs)
    }
}
