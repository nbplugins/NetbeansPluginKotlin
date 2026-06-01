// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Copyright 2026 nbplugins contributors
package io.github.nbplugins.kotlin.nbm.formatting.options

import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntry
import org.jetbrains.kotlin.idea.core.formatter.KotlinPackageEntryTable
import java.util.prefs.Preferences
import org.openide.util.NbPreferences

/**
 * Bridge between NetBeans [Preferences] and IntelliJ [KotlinCodeStyleSettings].
 *
 * <p>Preferences keys mirror IntelliJ field names so that the same key can in
 * principle be read/written by tooling that understands IntelliJ code-style XML.
 * Table fields ([KotlinCodeStyleSettings.PACKAGES_TO_USE_STAR_IMPORTS] and
 * [KotlinCodeStyleSettings.PACKAGES_IMPORT_LAYOUT]) are stored as serialised XML.
 *
 * <p>All reads and writes are performed on the supplied [Preferences] node;
 * callers are responsible for calling [Preferences.flush] after a batch of writes.
 */
object KotlinCodeStylePreferences {

    // Preference keys
    const val KEY_ALLOW_TRAILING_COMMA = "ALLOW_TRAILING_COMMA"
    const val KEY_ALLOW_TRAILING_COMMA_ON_CALL_SITE = "ALLOW_TRAILING_COMMA_ON_CALL_SITE"
    const val KEY_NAME_COUNT_TO_USE_STAR_IMPORT = "NAME_COUNT_TO_USE_STAR_IMPORT"
    const val KEY_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = "NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS"
    const val KEY_IMPORT_NESTED_CLASSES = "IMPORT_NESTED_CLASSES"
    const val KEY_PACKAGES_TO_USE_STAR_IMPORTS = "PACKAGES_TO_USE_STAR_IMPORTS"
    const val KEY_PACKAGES_IMPORT_LAYOUT = "PACKAGES_IMPORT_LAYOUT"

    /**
     * Loads Kotlin code-style settings from [prefs] into [settings].
     *
     * @param prefs    the source preferences node
     * @param settings the target code-style settings
     */
    fun load(prefs: Preferences, settings: CodeStyleSettings) {
        val ks = settings.kotlinCustomSettings
        ks.ALLOW_TRAILING_COMMA =
            prefs.getBoolean(KEY_ALLOW_TRAILING_COMMA, ks.ALLOW_TRAILING_COMMA)
        ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE =
            prefs.getBoolean(KEY_ALLOW_TRAILING_COMMA_ON_CALL_SITE, ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE)
        ks.NAME_COUNT_TO_USE_STAR_IMPORT =
            prefs.getInt(KEY_NAME_COUNT_TO_USE_STAR_IMPORT, ks.NAME_COUNT_TO_USE_STAR_IMPORT)
        ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS =
            prefs.getInt(KEY_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS, ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS)
        ks.IMPORT_NESTED_CLASSES =
            prefs.getBoolean(KEY_IMPORT_NESTED_CLASSES, ks.IMPORT_NESTED_CLASSES)

        val starEncoded = prefs.get(KEY_PACKAGES_TO_USE_STAR_IMPORTS, null)
        if (starEncoded != null) {
            tryDeserializeTable(starEncoded)?.let { ks.PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(it) }
        }
        val layoutEncoded = prefs.get(KEY_PACKAGES_IMPORT_LAYOUT, null)
        if (layoutEncoded != null) {
            tryDeserializeTable(layoutEncoded)?.let { ks.PACKAGES_IMPORT_LAYOUT.copyFrom(it) }
        }
    }

    /**
     * Saves Kotlin code-style settings from [settings] into [prefs].
     *
     * @param settings the source code-style settings
     * @param prefs    the target preferences node
     */
    fun save(settings: CodeStyleSettings, prefs: Preferences) {
        val ks = settings.kotlinCustomSettings
        prefs.putBoolean(KEY_ALLOW_TRAILING_COMMA, ks.ALLOW_TRAILING_COMMA)
        prefs.putBoolean(KEY_ALLOW_TRAILING_COMMA_ON_CALL_SITE, ks.ALLOW_TRAILING_COMMA_ON_CALL_SITE)
        prefs.putInt(KEY_NAME_COUNT_TO_USE_STAR_IMPORT, ks.NAME_COUNT_TO_USE_STAR_IMPORT)
        prefs.putInt(KEY_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS, ks.NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS)
        prefs.putBoolean(KEY_IMPORT_NESTED_CLASSES, ks.IMPORT_NESTED_CLASSES)
        prefs.put(KEY_PACKAGES_TO_USE_STAR_IMPORTS, serializeTable(KEY_PACKAGES_TO_USE_STAR_IMPORTS, ks.PACKAGES_TO_USE_STAR_IMPORTS))
        prefs.put(KEY_PACKAGES_IMPORT_LAYOUT, serializeTable(KEY_PACKAGES_IMPORT_LAYOUT, ks.PACKAGES_IMPORT_LAYOUT))
    }

    /**
     * Serialises a [KotlinPackageEntryTable] to a line-based string format:
     * each entry is {@code packageName:withSubpackages}, with special entries
     * encoded as {@code __ALL_OTHER__} or {@code __ALL_OTHER_ALIAS__}.
     *
     * @param table the table to serialise
     * @return the serialised string
     */
    private fun serializeTable(tag: String, table: KotlinPackageEntryTable): String {
        val sb = StringBuilder()
        for (i in 0 until table.entryCount) {
            val e = table.getEntryAt(i)
            when (e) {
                KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY -> sb.append("__ALL_OTHER__:true")
                KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY -> sb.append("__ALL_OTHER_ALIAS__:true")
                else -> sb.append("${e.packageName}:${e.withSubpackages}")
            }
            if (i < table.entryCount - 1) sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Deserialises a [KotlinPackageEntryTable] from the line-based format produced
     * by [serializeTable], returning {@code null} if the string cannot be parsed.
     *
     * @param encoded the encoded string
     * @return the table, or {@code null} on parse error
     */
    private fun tryDeserializeTable(encoded: String): KotlinPackageEntryTable? {
        return try {
            val table = KotlinPackageEntryTable()
            for (line in encoded.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val entry = when (trimmed) {
                    "__ALL_OTHER__:true" -> KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY
                    "__ALL_OTHER_ALIAS__:true" -> KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY
                    else -> {
                        val colon = trimmed.lastIndexOf(':')
                        if (colon < 0) return null
                        val pkg = trimmed.substring(0, colon)
                        val sub = trimmed.substring(colon + 1).toBoolean()
                        KotlinPackageEntry(pkg, sub)
                    }
                }
                table.addEntry(entry)
            }
            table
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the canonical [Preferences] node for Kotlin formatter settings.
     *
     * All callers (the Options UI panel and the formatter itself) must use this
     * method so that reads and writes target the same node.
     */
    fun prefs(): Preferences = NbPreferences.forModule(KotlinCodeStylePreferences::class.java)

    /**
     * Loads settings from [prefs] into the global formatter settings singleton.
     *
     * @param prefs the preferences node to read from
     */
    fun loadIntoGlobal(prefs: Preferences) {
        load(prefs, KotlinFormatterUtils.getSettings())
    }

    /**
     * Saves the global formatter settings singleton to [prefs].
     *
     * @param prefs the preferences node to write to
     */
    fun saveFromGlobal(prefs: Preferences) {
        save(KotlinFormatterUtils.getSettings(), prefs)
    }
}

/** Extension property for concise access to Kotlin custom settings. */
val CodeStyleSettings.kotlinCustomSettings: KotlinCodeStyleSettings
    get() = getCustomSettings(KotlinCodeStyleSettings::class.java)
