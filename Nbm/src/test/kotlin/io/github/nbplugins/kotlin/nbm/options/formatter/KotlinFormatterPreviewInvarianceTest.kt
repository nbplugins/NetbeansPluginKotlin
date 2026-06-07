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
 * Verifies that the [KotlinFormattingPreviewPane] emits real tab characters
 * when **Use tab character** is on, and spaces-only when it is off.
 *
 * <p>When tabs are enabled the preview also sets
 * {@code NON_PRINTABLE_CHARACTERS_VISIBLE = true} on the document so the
 * NetBeans EditorKit renders the tab arrows (→) — this is tested visually
 * via manual testing; these tests cover only the text content.
 */
class KotlinFormatterPreviewInvarianceTest : KotlinTestCase(
    "KotlinFormatterPreviewInvarianceTest", "formatting"
) {

    /**
     * When Use tab character is on the formatter must emit at least one `\t`
     * in the preview text.
     */
    fun testPreviewWithTabsContainsTabCharacter() {
        val text = renderPreview(useTab = true)
        assertTrue(
            "preview text must contain \\t when Use tab character is on",
            text.contains('\t')
        )
    }

    /**
     * When Use tab character is off the formatter must emit no `\t` characters.
     */
    fun testPreviewWithSpacesContainsNoTabCharacter() {
        val text = renderPreview(useTab = false)
        assertFalse(
            "preview text must not contain \\t when Use tab character is off",
            text.contains('\t')
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
