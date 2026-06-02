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
import org.jetbrains.kotlin.idea.formatter.KotlinObsoleteStyleGuide
import org.jetbrains.kotlin.idea.formatter.KotlinOfficialStyleGuide
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel

/**
 * Toolbar panel shown at the top of Tools → Options → Kotlin.
 *
 * <p>Contains a "Style:" label and a drop-down listing the three built-in
 * Kotlin code-style presets. Selecting a preset creates a fresh
 * [CodeStyleSettings] with the preset applied and passes it to [onStyleApplied].
 *
 * @param onStyleApplied called with the fully-populated [CodeStyleSettings] when
 *                       the user picks a preset from the drop-down
 */
class KotlinStyleBar(
    private val onStyleApplied: (CodeStyleSettings) -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    /** Built-in code-style presets shown in the drop-down. */
    private enum class BuiltInStyle(val title: String, val codeStyleId: String?) {
        OFFICIAL(KotlinOfficialStyleGuide.CODE_STYLE_TITLE, KotlinOfficialStyleGuide.CODE_STYLE_ID),
        OBSOLETE(KotlinObsoleteStyleGuide.CODE_STYLE_TITLE, KotlinObsoleteStyleGuide.CODE_STYLE_ID),
        DEFAULTS("IDE Defaults", null)
    }

    private val combo = JComboBox(BuiltInStyle.entries.toTypedArray())

    /** True while [setCurrentStyle] is updating the combo; suppresses the action listener. */
    private var isLoading = false

    init {
        add(JLabel("Style:"))
        combo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component = super.getListCellRendererComponent(
                list, (value as? BuiltInStyle)?.title ?: value, index, isSelected, cellHasFocus
            )
        }
        add(combo)
        combo.addActionListener { if (!isLoading) applySelected() }
    }

    /**
     * Synchronises the combo selection with [codeStyleId] without firing the
     * action listener.
     *
     * @param codeStyleId the value of [KotlinCodeStyleSettings.CODE_STYLE_DEFAULTS]
     *                    from the current preferences, or {@code null} for IDE defaults
     */
    fun setCurrentStyle(codeStyleId: String?) {
        isLoading = true
        combo.selectedItem = BuiltInStyle.entries.firstOrNull { it.codeStyleId == codeStyleId }
            ?: BuiltInStyle.DEFAULTS
        isLoading = false
    }

    private fun applySelected() {
        val settings = CodeStyleSettings()
        when (combo.selectedItem as BuiltInStyle) {
            BuiltInStyle.OFFICIAL -> KotlinOfficialStyleGuide.apply(settings)
            BuiltInStyle.OBSOLETE -> KotlinObsoleteStyleGuide.apply(settings)
            BuiltInStyle.DEFAULTS -> {}
        }
        onStyleApplied(settings)
    }
}
