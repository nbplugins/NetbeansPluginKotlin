// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.formatting.options

import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.formatter.KotlinCodeStyleSerializer
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.openide.util.NbPreferences
import java.util.logging.Level
import java.util.logging.Logger
import java.util.prefs.Preferences

/**
 * Bridge between NetBeans [Preferences] and IntelliJ [KotlinCodeStyleSettings].
 *
 * <p>Settings are persisted as XML strings in the IDEA {@code JetCodeStyleSettings}
 * format via [KotlinCodeStyleSerializer], so the preference node can be read by
 * tooling that understands IntelliJ code-style XML. Only fields that differ from the
 * IDEA defaults are stored (diff-from-defaults encoding).
 *
 * <p>[IndentOptions] is stored separately under [PREFS_KEY_INDENT] as a compact XML
 * element with four attributes.
 */
object KotlinCodeStylePreferences {

    private val LOG = Logger.getLogger(KotlinCodeStylePreferences::class.java.name)

    /** Preferences key for the serialized [KotlinCodeStyleSettings] XML. */
    const val PREFS_KEY_KOTLIN = "kotlinCodeStyleSettings"

    /** Preferences key for the serialized indent options XML. */
    const val PREFS_KEY_INDENT = "indentOptions"

    /**
     * Returns the canonical [Preferences] node for Kotlin formatter settings.
     *
     * All callers must use this method so that reads and writes target the same node.
     */
    fun prefs(): Preferences = NbPreferences.forModule(KotlinCodeStylePreferences::class.java)

    /**
     * Serializes [settings] to [prefs].
     *
     * [KotlinCodeStyleSettings] is stored as a diff-from-defaults XML string under
     * [PREFS_KEY_KOTLIN]; indent options are stored under [PREFS_KEY_INDENT].
     * Serialization errors are logged as warnings and the key is left unchanged.
     *
     * @param settings the source code-style settings
     * @param prefs    the target preferences node
     */
    fun save(settings: CodeStyleSettings, prefs: Preferences) {
        try {
            prefs.put(PREFS_KEY_KOTLIN, KotlinCodeStyleSerializer.serializeKotlinSettings(settings))
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to serialize Kotlin code style settings", e)
        }
        try {
            prefs.put(PREFS_KEY_INDENT, KotlinCodeStyleSerializer.serializeIndentOptions(settings))
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to serialize indent options", e)
        }
    }

    /**
     * Deserializes settings from [prefs] into [settings].
     *
     * Missing keys are silently skipped (existing field values in [settings] are kept
     * as fallback). Malformed XML is logged as a warning and skipped.
     *
     * @param prefs    the source preferences node
     * @param settings the target code-style settings
     */
    fun load(prefs: Preferences, settings: CodeStyleSettings) {
        prefs.get(PREFS_KEY_KOTLIN, null)?.let { xml ->
            try {
                KotlinCodeStyleSerializer.deserializeKotlinSettings(xml, settings)
            } catch (e: Exception) {
                LOG.log(Level.WARNING, "Failed to deserialize Kotlin code style settings", e)
            }
        }
        prefs.get(PREFS_KEY_INDENT, null)?.let { xml ->
            try {
                KotlinCodeStyleSerializer.deserializeIndentOptions(xml, settings)
            } catch (e: Exception) {
                LOG.log(Level.WARNING, "Failed to deserialize indent options", e)
            }
        }
    }

    /**
     * Loads settings from [prefs] into the global formatter settings singleton.
     *
     * @param prefs the preferences node to read from
     */
    fun loadIntoGlobal(prefs: Preferences) = load(prefs, KotlinFormatterUtils.getSettings())

    /**
     * Saves the global formatter settings singleton to [prefs].
     *
     * @param prefs the preferences node to write to
     */
    fun saveFromGlobal(prefs: Preferences) = save(KotlinFormatterUtils.getSettings(), prefs)
}

/** Extension property for concise access to Kotlin custom settings. */
val CodeStyleSettings.kotlinCustomSettings: KotlinCodeStyleSettings
    get() = getCustomSettings(KotlinCodeStyleSettings::class.java)
