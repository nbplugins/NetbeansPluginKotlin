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

import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import java.awt.Component
import java.util.prefs.Preferences
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Sub-panel for "Other" Kotlin code-style settings shown in
 * Tools → Options → Kotlin → Other.
 *
 * <p>Currently exposes two trailing-comma options whose defaults match the
 * IntelliJ IDEA Kotlin code style defaults ({@code false}).
 *
 * @param onChange called whenever any control changes value; forwarded to the
 *                 parent panel so the Options dialog can track unsaved changes
 */
class KotlinFormatterOtherPanel(private val onChange: () -> Unit) : JPanel() {

    private val trailingCommaDecl =
        JCheckBox("Allow trailing comma in declarations")
    private val trailingCommaCall =
        JCheckBox("Allow trailing comma on call site")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(8, 8, 8, 8)
        trailingCommaDecl.alignmentX = Component.LEFT_ALIGNMENT
        trailingCommaCall.alignmentX = Component.LEFT_ALIGNMENT
        add(trailingCommaDecl)
        add(trailingCommaCall)
        trailingCommaDecl.addActionListener { onChange() }
        trailingCommaCall.addActionListener { onChange() }
    }

    /**
     * Populates the checkboxes from [prefs] by deserializing into a temporary
     * settings object and reading the trailing-comma fields.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = com.intellij.psi.codeStyle.CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        val ks = tmp.kotlinCustomSettings
        trailingCommaDecl.isSelected = ks.ALLOW_TRAILING_COMMA
        trailingCommaCall.isSelected = ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE
    }

    /** Returns whether the "Allow trailing comma in declarations" checkbox is selected. */
    fun isTrailingCommaDeclSelected(): Boolean = trailingCommaDecl.isSelected

    /** Returns whether the "Allow trailing comma on call site" checkbox is selected. */
    fun isTrailingCommaCallSelected(): Boolean = trailingCommaCall.isSelected

    /** Sets the "Allow trailing comma in declarations" checkbox state without firing onChange. */
    fun setTrailingCommaDeclSelected(selected: Boolean) { trailingCommaDecl.isSelected = selected }

    /**
     * Writes the checkboxes' current state to [prefs], merging with any existing
     * settings so that other fields are not overwritten.
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        val tmp = com.intellij.psi.codeStyle.CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        val ks = tmp.kotlinCustomSettings
        ks.ALLOW_TRAILING_COMMA = trailingCommaDecl.isSelected
        ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE = trailingCommaCall.isSelected
        KotlinCodeStylePreferences.save(tmp, prefs)
    }
}
