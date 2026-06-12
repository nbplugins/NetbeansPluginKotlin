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
package io.github.nbplugins.kotlin.nbm.hints

import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaAddGetterIntention
import io.github.nbplugins.kotlin.nbm.hints.intentions.KaIfToWhenIntention

/**
 * Tests that [KaApplicableIntention] subclasses which generate multi-line code respect the active
 * formatter settings (global and per-project).
 *
 * Each test uses [KaIntentionTestBase.doTestExact] (exact character comparison, no whitespace
 * normalisation) to verify that the formatter was actually called — structural equivalence is not
 * sufficient here because the whole point is to confirm correct indentation.
 *
 * The 2-space indent test temporarily pushes a custom [com.intellij.psi.codeStyle.CodeStyleSettings]
 * via [KotlinFormatterUtils.pushSettings]. Because [io.github.nbplugins.kotlin.nbm.reformatting.format]
 * resolves project settings through [io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage]
 * which falls back to [KotlinFormatterUtils.getSettings] when no per-project file is present,
 * the pushed settings are picked up by the formatter for the duration of the test.
 */
class KaFormatterSettingsIntentionTest : KaIntentionTestBase(
    "KaFormatterSettingsIntention test",
    "intentions"
) {

    /**
     * Verifies that "Replace 'if' with 'when'" produces properly indented `when` branches.
     *
     * Before the fix the branches were at column 0; after the fix `format()` is called
     * on the changed range so branches align with the block body (4-space default indent).
     */
    fun testKaIfToWhenProducesIndentedBranches() = doTestExact("ifToWhen") { doc, kaKtFile, psi ->
        KaIfToWhenIntention(doc, kaKtFile, psi)
    }

    /**
     * Verifies that "Add getter" respects a non-default 2-space indent setting.
     *
     * Pushes a [com.intellij.psi.codeStyle.CodeStyleSettings] clone with `INDENT_SIZE = 2`
     * before running the intention. The getter accessor must use a 2-space continuation step
     * relative to the property keyword, not the 4-space default.
     */
    fun testKaAddGetterRespectsCustomIndent() {
        val twoSpaceSettings = KotlinFormatterUtils.getSettings().clone()
        twoSpaceSettings.getIndentOptions()?.apply {
            INDENT_SIZE = 2
            TAB_SIZE = 2
            CONTINUATION_INDENT_SIZE = 2
        }
        KotlinFormatterUtils.pushSettings(twoSpaceSettings)
        try {
            doTestExact("kaAddGetterTwoSpace") { doc, kaKtFile, psi ->
                KaAddGetterIntention(doc, kaKtFile, psi)
            }
        } finally {
            KotlinFormatterUtils.popSettings()
        }
    }
}
