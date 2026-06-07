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
import javax.swing.border.TitledBorder

/**
 * Sub-panel for "Other" Kotlin code-style settings shown in
 * Tools → Options → Kotlin → Other.
 *
 * <p>Mirrors the IDEA layout: a "Trailing Comma" group with a master
 * "Use trailing comma" toggle and 10 indented context sub-items.
 * Declaration-site items (Type parameter list … Context receiver list) are
 * read-only informational labels — they are always checked because they
 * represent every context covered by {@code ALLOW_TRAILING_COMMA}.
 * Call-site items (Collection literal expression … Value argument list) are
 * individually clickable but collectively linked to
 * {@code ALLOW_TRAILING_COMMA_ON_CALL_SITE} (a single boolean).
 *
 * @param onChange called whenever any interactive control changes value
 */
class KotlinFormatterOtherPanel(private val onChange: () -> Unit) : JPanel() {

    private val cbMaster = JCheckBox("Use trailing comma")

    /** Read-only items — always shown selected; just greyed when master is off. */
    private val declItems: List<JCheckBox> = listOf(
        JCheckBox("Type parameter list"),
        JCheckBox("Destructuring declaration"),
        JCheckBox("When entry"),
        JCheckBox("Function literal"),
        JCheckBox("Value parameter list"),
        JCheckBox("Context receiver list"),
    )

    /** Interactive items — all linked to ALLOW_TRAILING_COMMA_ON_CALL_SITE. */
    private val callSiteItems: List<JCheckBox> = listOf(
        JCheckBox("Collection literal expression"),
        JCheckBox("Type argument list"),
        JCheckBox("Indices"),
        JCheckBox("Value argument list"),
    )

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(8, 8, 8, 8)

        val group = JPanel()
        group.layout = BoxLayout(group, BoxLayout.Y_AXIS)
        group.border = TitledBorder("Trailing Comma")
        group.alignmentX = Component.LEFT_ALIGNMENT

        cbMaster.alignmentX = Component.LEFT_ALIGNMENT
        group.add(cbMaster)

        declItems.forEach { cb ->
            cb.isSelected = true
            cb.isEnabled = false          // always greyed — non-interactive
            cb.alignmentX = Component.LEFT_ALIGNMENT
            cb.border = EmptyBorder(0, 20, 0, 0)
            group.add(cb)
        }

        callSiteItems.forEach { cb ->
            cb.isEnabled = false          // enabled only when master is on
            cb.alignmentX = Component.LEFT_ALIGNMENT
            cb.border = EmptyBorder(0, 20, 0, 0)
            group.add(cb)
        }

        add(group)

        cbMaster.addActionListener {
            updateSubItemsEnabled()
            onChange()
        }

        // Clicking any call-site item propagates the value to all others.
        callSiteItems.forEach { cb ->
            cb.addActionListener {
                val v = cb.isSelected
                callSiteItems.forEach { it.isSelected = v }
                onChange()
            }
        }
    }

    /**
     * Populates the controls from [prefs] by deserializing into a temporary
     * settings object and reading the trailing-comma fields.
     *
     * @param prefs source preferences node
     */
    fun load(prefs: Preferences) {
        val tmp = com.intellij.psi.codeStyle.CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        val ks = tmp.kotlinCustomSettings
        cbMaster.isSelected = ks.ALLOW_TRAILING_COMMA
        callSiteItems.forEach { it.isSelected = ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE }
        updateSubItemsEnabled()
    }

    /**
     * Writes the controls' current state to [prefs], merging with any existing
     * settings so that other fields are not overwritten.
     *
     * @param prefs target preferences node
     */
    fun store(prefs: Preferences) {
        val tmp = com.intellij.psi.codeStyle.CodeStyleSettings()
        KotlinCodeStylePreferences.load(prefs, tmp)
        val ks = tmp.kotlinCustomSettings
        ks.ALLOW_TRAILING_COMMA = cbMaster.isSelected
        ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE =
            cbMaster.isSelected && callSiteItems.first().isSelected
        KotlinCodeStylePreferences.save(tmp, prefs)
    }

    /** Enables/disables sub-items based on the master checkbox state. */
    private fun updateSubItemsEnabled() {
        val on = cbMaster.isSelected
        // Declaration items stay non-interactive (isEnabled=false always);
        // only call-site items become interactive when master is on.
        callSiteItems.forEach { it.isEnabled = on }
    }
}
