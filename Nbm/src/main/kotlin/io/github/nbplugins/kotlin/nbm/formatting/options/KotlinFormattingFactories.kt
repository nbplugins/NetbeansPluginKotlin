// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.formatting.options

import org.jetbrains.kotlin.idea.formatter.ImportSettingsPanelWrapper
import org.jetbrains.kotlin.idea.formatter.KotlinOtherSettingsPanel
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import org.netbeans.modules.options.editor.spi.PreferencesCustomizer
import org.netbeans.api.editor.mimelookup.MimeLookup
import org.netbeans.api.editor.mimelookup.MimePath
import java.util.prefs.Preferences

/**
 * Factory for the "Other" Kotlin formatter settings panel.
 *
 * <p>Exposes trailing-comma options to Tools → Options → Editors → Formatting → Kotlin → Other.
 * Registered in layer.xml under {@code Editors/Formatting/text/x-kotlin}.
 */
object KotlinOtherSettingsFactory : PreferencesCustomizer.Factory {

    /**
     * Creates a [PreferencesCustomizer] backed by [KotlinOtherSettingsPanel].
     *
     * @param prefs the backing preferences node
     * @return the customizer
     */
    override fun create(prefs: Preferences): PreferencesCustomizer {
        val settings = KotlinFormatterUtils.getSettings()
        return KotlinFormattingPanelCustomizer(
            id = "kotlin-other",
            prefs = prefs,
            panel = KotlinOtherSettingsPanel(settings)
        )
    }

    /** @return a [KotlinOtherSettingsFactory] instance for layer.xml {@code methodvalue} registration. */
    @JvmStatic
    fun getController(): PreferencesCustomizer.Factory = this
}

/**
 * Factory for the "Imports" Kotlin formatter settings panel.
 *
 * <p>Exposes star-import thresholds, import-layout order, and nested-class import options
 * to Tools → Options → Editors → Formatting → Kotlin → Imports.
 * Registered in layer.xml under {@code Editors/Formatting/text/x-kotlin}.
 */
object KotlinImportsSettingsFactory : PreferencesCustomizer.Factory {

    /**
     * Creates a [PreferencesCustomizer] backed by [ImportSettingsPanelWrapper].
     *
     * @param prefs the backing preferences node
     * @return the customizer
     */
    override fun create(prefs: Preferences): PreferencesCustomizer {
        val settings = KotlinFormatterUtils.getSettings()
        return KotlinFormattingPanelCustomizer(
            id = "kotlin-imports",
            prefs = prefs,
            panel = ImportSettingsPanelWrapper(settings)
        )
    }

    /** @return a [KotlinImportsSettingsFactory] instance for layer.xml {@code methodvalue} registration. */
    @JvmStatic
    fun getController(): PreferencesCustomizer.Factory = this
}
