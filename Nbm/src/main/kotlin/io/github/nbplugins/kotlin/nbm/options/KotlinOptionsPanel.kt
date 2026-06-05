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
package io.github.nbplugins.kotlin.nbm.options

import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterBlankLinesPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterIndentPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormatterOtherPanel
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinFormattingPreviewPane
import io.github.nbplugins.kotlin.nbm.options.formatter.KotlinStyleBar
import java.awt.BorderLayout
import java.util.prefs.Preferences
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane

/**
 * Root panel for Tools → Options → Kotlin.
 *
 * <p>Layout: a [KotlinStyleBar] (code-style preset selector) at the top, with
 * a [JSplitPane] below that shows the formatter tabs on the left and a live
 * [KotlinFormattingPreviewPane] on the right.
 *
 * <p>The [onChange] callback is forwarded to [KotlinOptionsPanelController] so
 * that the Options dialog can track whether unsaved changes exist.
 *
 * @param onChange called whenever any control in any sub-panel changes value
 */
class KotlinOptionsPanel(private val onChange: () -> Unit) : JPanel(BorderLayout()) {

    private val indentPanel = KotlinFormatterIndentPanel(::onSettingChanged)
    private val blankLinesPanel = KotlinFormatterBlankLinesPanel(::onSettingChanged)
    private val otherPanel = KotlinFormatterOtherPanel(::onSettingChanged)

    private val previewPane = KotlinFormattingPreviewPane(::collectSettingsInto)
    private val styleBar = KotlinStyleBar(::onStyleApplied, ::collectCurrentSettings)

    private val tabs = JTabbedPane()
    private val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, previewPane).apply {
        resizeWeight = 0.55
    }

    init {
        tabs.addTab("Tabs & Indent", indentPanel)
        tabs.addTab("Blank Lines", blankLinesPanel)
        tabs.addTab("Other", otherPanel)
        add(styleBar, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)
    }

    override fun addNotify() {
        super.addNotify()
        splitPane.setDividerLocation(0.55)
    }

    /**
     * Populates all sub-panels from [prefs] and syncs the style bar.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        styleBar.setCurrentStyle(tmp.kotlinCustomSettings.CODE_STYLE_DEFAULTS)
        indentPanel.load(prefs)
        blankLinesPanel.load(prefs)
        otherPanel.load(prefs)
        previewPane.scheduleRefresh()
    }

    /**
     * Writes all sub-panels' current state to [prefs].
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        indentPanel.store(prefs)
        blankLinesPanel.store(prefs)
        otherPanel.store(prefs)
    }

    private fun onSettingChanged() {
        onChange()
        previewPane.scheduleRefresh()
    }

    private fun onStyleApplied(settings: CodeStyleSettings) {
        val prefs = KotlinCodeStylePreferences.prefs()
        KotlinCodeStylePreferences.save(settings, prefs)
        load(prefs)
        onChange()
    }

    /**
     * Returns a [CodeStyleSettings] reflecting the live panel state (including any
     * unsaved changes), overlaid on the currently persisted Kotlin custom settings.
     * Used by [KotlinStyleBar] as the base when applying a style preset so that
     * fields the preset does not explicitly define are preserved.
     */
    private fun collectCurrentSettings(): CodeStyleSettings {
        val settings = CodeStyleSettings()
        // Seed with persisted Kotlin custom settings (CODE_STYLE_DEFAULTS,
        // CONTINUATION_INDENT_* flags, etc.) for fields not shown in any panel.
        KotlinCodeStylePreferences.load(KotlinCodeStylePreferences.prefs(), settings)
        // Override with live panel values (may have unsaved changes).
        settings.indentOptions.apply {
            TAB_SIZE = indentPanel.getTabSize()
            INDENT_SIZE = indentPanel.getIndentSize()
            CONTINUATION_INDENT_SIZE = indentPanel.getContinuationIndentSize()
            USE_TAB_CHARACTER = indentPanel.isUseTabCharacter()
        }
        settings.kotlinCustomSettings.apply {
            ALLOW_TRAILING_COMMA = otherPanel.isTrailingCommaDeclSelected()
            ALLOW_TRAILING_COMMA_ON_CALL_SITE = otherPanel.isTrailingCommaCallSelected()
            BLANK_LINES_AROUND_BLOCK_WHEN_BRANCHES = blankLinesPanel.getBlankLinesWhenBranches()
            BLANK_LINES_BEFORE_DECLARATION_WITH_COMMENT_OR_ANNOTATION_ON_SEPARATE_LINE =
                blankLinesPanel.getBlankLinesDeclWithAnnotation()
        }
        return settings
    }

    private fun collectSettingsInto(prefs: Preferences) {
        // Seed with the persisted KotlinCodeStyleSettings so style-preset fields
        // (CODE_STYLE_DEFAULTS, CONTINUATION_INDENT_*, WRAP_*, etc.) survive into the
        // preview. Panel stores overlay only their own fields on top of this base.
        KotlinCodeStylePreferences.prefs()
            .get(KotlinCodeStylePreferences.PREFS_KEY_KOTLIN, null)
            ?.let { prefs.put(KotlinCodeStylePreferences.PREFS_KEY_KOTLIN, it) }
        indentPanel.store(prefs)
        blankLinesPanel.store(prefs)
        otherPanel.store(prefs)
    }
}
