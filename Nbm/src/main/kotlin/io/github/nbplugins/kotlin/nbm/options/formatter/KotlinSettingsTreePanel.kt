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
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.idea.formatter.KotlinLanguageCodeStyleSettingsProvider
import java.awt.BorderLayout
import java.util.prefs.Preferences
import javax.swing.JPanel
import javax.swing.tree.DefaultTreeModel

/**
 * Formatter settings panel backed by an [OptionTreePanel] whose option groups are
 * auto-generated from [KotlinLanguageCodeStyleSettingsProvider.customizeSettings].
 *
 * <p>For [LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS],
 * three global controls (Hard wrap at, Wrap on typing, Visual guides) are prepended
 * as top-level tree leaves above the option groups.
 *
 * <p>The [store] method uses merge-store: it loads the current prefs state first,
 * overlays only the fields owned by this panel, then saves — so concurrent stores
 * from sibling panels do not overwrite each other.
 */
class KotlinSettingsTreePanel(
    private val settingsType: LanguageCodeStyleSettingsProvider.SettingsType,
    private val onChange: () -> Unit
) : JPanel(BorderLayout()) {

    /** Live settings object; reassigned on each [load]. Lambdas in OptionItems read from it. */
    internal var settings: CodeStyleSettings = CodeStyleSettings()

    private var isLoading = false
    private val fireChange: () -> Unit = { if (!isLoading) onChange() }

    private val collector = KotlinStyleOptionsCollector(
        settingsTypeName = settingsType.name,
        commonProvider = { settings.getCommonSettings(KotlinLanguage.INSTANCE) },
        kotlinProvider = { settings.kotlinCustomSettings }
    )

    internal val treePanel: OptionTreePanel

    private val isWrappingTab =
        settingsType == LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS

    init {
        val provider = KotlinLanguageCodeStyleSettingsProvider()
        provider.customizeSettings(collector, settingsType)

        val collected = sortGroups(collector.build())
        val groups = if (isWrappingTab) buildGlobalWrapHeaderGroup() + collected else collected

        treePanel = OptionTreePanel(groups, fireChange)
        add(treePanel, BorderLayout.CENTER)
    }

    /**
     * Builds an empty-label group containing the three global wrap controls that render
     * as the first root-level tree leaves (Hard wrap at / Wrap on typing / Visual guides).
     */
    private fun buildGlobalWrapHeaderGroup(): List<OptionGroup> {
        val commonSettings = { settings.getCommonSettings(KotlinLanguage.INSTANCE) }
        val items = listOf<OptionItem>(
            IntFieldItem(
                label = "Hard wrap at",
                get = { commonSettings().RIGHT_MARGIN },
                set = { v -> commonSettings().RIGHT_MARGIN = v },
                min = -1,
                max = 999
            ),
            WrapItem(
                label = "Wrap on typing",
                displayOptions = arrayOf("Default", "No wrap", "Wrap"),
                storedValues = WRAP_ON_TYPING_VALUES,
                get = { commonSettings().WRAP_ON_TYPING },
                set = { v -> commonSettings().WRAP_ON_TYPING = v }
            ),
            TextFieldItem(
                label = "Visual guides",
                get = { commonSettings().getSoftMargins().joinToString(", ") },
                set = { txt ->
                    val margins = if (txt.isBlank()) emptyList()
                    else txt.split(",").mapNotNull { it.trim().toIntOrNull() }
                    commonSettings().setSoftMargins(margins)
                },
                columns = 16
            )
        )
        return listOf(OptionGroup("", items))
    }

    private fun sortGroups(groups: List<OptionGroup>): List<OptionGroup> {
        val groupOrder = GROUP_ORDER[settingsType] ?: return groups
        val itemOrder = ITEM_ORDER[settingsType] ?: emptyMap()
        return groups
            .sortedWith(Comparator { a, b ->
                val ia = groupOrder.indexOf(a.label).let { if (it < 0) Int.MAX_VALUE else it }
                val ib = groupOrder.indexOf(b.label).let { if (it < 0) Int.MAX_VALUE else it }
                ia.compareTo(ib)
            })
            .map { group ->
                val order = itemOrder[group.label] ?: return@map group
                val sorted = group.items.sortedWith(Comparator { a, b ->
                    val ia = order.indexOf(a.label).let { if (it < 0) Int.MAX_VALUE else it }
                    val ib = order.indexOf(b.label).let { if (it < 0) Int.MAX_VALUE else it }
                    ia.compareTo(ib)
                })
                OptionGroup(group.label, sorted)
            }
    }

    /**
     * Loads settings from [prefs] and refreshes the tree display.
     */
    fun load(prefs: Preferences) {
        val tmp = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(tmp)
        KotlinCodeStylePreferences.load(prefs, tmp)
        isLoading = true
        try {
            settings = tmp
            (treePanel.tree.model as DefaultTreeModel).reload()
            var i = 0
            while (i < treePanel.tree.rowCount) treePanel.tree.expandRow(i++)
        } finally {
            isLoading = false
        }
    }

    /**
     * Writes only this panel's fields to [prefs] using merge-store.
     */
    fun store(prefs: Preferences) {
        val base = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(base)
        KotlinCodeStylePreferences.load(prefs, base)

        val commonSrc = settings.getCommonSettings(KotlinLanguage.INSTANCE)
        val kotlinSrc = settings.kotlinCustomSettings
        val commonDst = base.getCommonSettings(KotlinLanguage.INSTANCE)
        val kotlinDst = base.kotlinCustomSettings

        for (entry in collector.fieldEntries()) {
            if (entry.isCustom) {
                val field = runCatching { KotlinCodeStyleSettings::class.java.getField(entry.fieldName) }.getOrNull()
                    ?: continue
                copyField(field, kotlinSrc, kotlinDst)
            } else {
                val field = runCatching { CommonCodeStyleSettings::class.java.getField(entry.fieldName) }.getOrNull()
                    ?: continue
                copyField(field, commonSrc, commonDst)
            }
        }

        if (isWrappingTab) {
            commonDst.RIGHT_MARGIN = commonSrc.RIGHT_MARGIN
            commonDst.WRAP_ON_TYPING = commonSrc.WRAP_ON_TYPING
            commonDst.setSoftMargins(commonSrc.getSoftMargins())
        }

        KotlinCodeStylePreferences.save(base, prefs)
    }

    private fun copyField(field: java.lang.reflect.Field, src: Any, dst: Any) {
        when (field.type) {
            java.lang.Boolean.TYPE -> field.setBoolean(dst, field.getBoolean(src))
            java.lang.Integer.TYPE -> field.setInt(dst, field.getInt(src))
        }
    }

    companion object {
        /** Int values for the "Wrap on typing" combo (indices 0, 1, 2 → Default, No wrap, Wrap). */
        internal val WRAP_ON_TYPING_VALUES = intArrayOf(-1, 0, 1)

        /**
         * Preferred group display order per settings type. The WRAPPING_AND_BRACES_SETTINGS
         * sequence matches IDEA's emission order in
         * `KotlinLanguageCodeStyleSettingsProvider.customizeSettings()`.
         * Groups not present in the list are sorted to the end in their natural order.
         */
        private val GROUP_ORDER: Map<LanguageCodeStyleSettingsProvider.SettingsType, List<String>> = mapOf(
            LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS to listOf(
                "Before parentheses",
                "Around operators",
                "Other"
            ),
            LanguageCodeStyleSettingsProvider.SettingsType.COMMENTER_SETTINGS to listOf(
                "Comment Code"
            ),
            LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS to listOf(
                "Keep when reformatting",
                "Extends/implements list",
                "Function declaration parameters",
                "Function call arguments",
                "Function parentheses",
                "Chained function calls",
                "'if()' statement",
                "do...while() statement",
                "try statement(s)",
                "Binary operations",
                // Slots reserved for groups not yet emitted by the current
                // KotlinLanguageCodeStyleSettingsProvider submodule pin (242 era).
                // They become visible automatically when the submodule is bumped to a
                // newer Kotlin plugin that emits the corresponding fields.
                "Property context parameters",
                "Function context parameters",
                "'when' statements",
                "Braces",
                "Expression body functions",
                "Elvis expressions",
                "Assignment statement",
                "Enum constants",
                "Annotations"
            )
        )

        /**
         * Preferred item display order within specific groups, per settings type.
         * Items not listed appear after the listed ones in their natural order.
         */
        private val ITEM_ORDER: Map<LanguageCodeStyleSettingsProvider.SettingsType, Map<String, List<String>>> = mapOf(
            LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS to mapOf(
                "Other" to listOf("Before comma", "After comma")
            ),
            LanguageCodeStyleSettingsProvider.SettingsType.WRAPPING_AND_BRACES_SETTINGS to mapOf(
                "Keep when reformatting" to listOf(
                    "Line breaks",
                    "Comment at first column"
                ),
                "Function declaration parameters" to listOf(
                    "Function declaration parameters",
                    "Align when multiline",
                    "New line after '('",
                    "Place ')' on new line",
                    "Use continuation indent"
                ),
                "Function call arguments" to listOf(
                    "Function call arguments",
                    "Align when multiline",
                    "New line after '('",
                    "Place ')' on new line",
                    "Use continuation indent"
                ),
                "Extends/implements list" to listOf(
                    "Extends/implements list",
                    "Align when multiline",
                    "Use continuation indent"
                )
            )
        )
    }
}
