// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
//
// Plain-Swing replacement for the DSL-based KotlinOtherSettingsPanel.
// The original uses `panel {}` (Kotlin UI DSL) which requires platform-impl.
package org.jetbrains.kotlin.idea.formatter

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.psi.codeStyle.CodeStyleSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings panel for the "Other" Kotlin code-style category.
 *
 * <p>Exposes the trailing-comma options
 * ({@code ALLOW_TRAILING_COMMA} and {@code ALLOW_TRAILING_COMMA_ON_CALL_SITE})
 * from {@code KotlinCodeStyleSettings}.
 *
 * @param settings the live code-style settings model
 */
class KotlinOtherSettingsPanel(settings: CodeStyleSettings) :
    CodeStyleAbstractPanel(null, null, settings) {

    private val cbTrailingCommaDecl = JCheckBox("Use trailing comma")
    private val cbTrailingCommaCall = JCheckBox("Use trailing comma on call site")

    private val panel: JPanel = JPanel(BorderLayout()).apply {
        val group = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = BorderFactory.createTitledBorder("Trailing Comma")
            add(cbTrailingCommaDecl)
            add(cbTrailingCommaCall)
        }
        add(group, BorderLayout.NORTH)
    }

    override fun getRightMargin(): Int = -1

    override fun createHighlighter(scheme: EditorColorsScheme?) =
        throw UnsupportedOperationException()

    override fun getFileType() = throw UnsupportedOperationException()

    override fun getPreviewText(): String? = null

    override fun apply(settings: CodeStyleSettings) {
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA = cbTrailingCommaDecl.isSelected
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA_ON_CALL_SITE = cbTrailingCommaCall.isSelected
    }

    override fun isModified(settings: CodeStyleSettings): Boolean =
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA != cbTrailingCommaDecl.isSelected ||
        settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA_ON_CALL_SITE != cbTrailingCommaCall.isSelected

    override fun getPanel(): JComponent = panel

    override fun resetImpl(settings: CodeStyleSettings) {
        cbTrailingCommaDecl.isSelected = settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA
        cbTrailingCommaCall.isSelected = settings.kotlinCustomSettings.ALLOW_TRAILING_COMMA_ON_CALL_SITE
    }

    override fun getTabTitle(): String = "Other"
}
