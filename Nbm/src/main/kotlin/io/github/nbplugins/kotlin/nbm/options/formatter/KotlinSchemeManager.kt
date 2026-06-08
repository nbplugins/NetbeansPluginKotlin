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
import io.github.nbplugins.kotlin.formatter.KotlinCodeStyleSerializer
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.kotlinCustomSettings
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.formatter.KotlinCommonCodeStyleSettings
import org.openide.util.NbPreferences
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import java.util.prefs.Preferences

/**
 * Singleton service for managing named custom Kotlin code-style schemes.
 *
 * <p>Each custom scheme is a snapshot of [CodeStyleSettings] stored as child nodes
 * under a dedicated [NbPreferences] root. The main formatter preferences node
 * (see [KotlinCodeStylePreferences.prefs]) continues to hold the **active** settings
 * used by the formatter at runtime; custom scheme nodes are independent snapshots.
 *
 * <p>Storage layout:
 * <pre>
 * NbPreferences.forModule(KotlinSchemeManager)
 *   └── "customSchemes"
 *         ├── encoded-scheme-name
 *         │     ├── "name"                          = actual display name
 *         │     ├── "basePresetId"                  = "KOTLIN_OFFICIAL" | "KOTLIN_OBSOLETE" | null
 *         │     ├── "kotlinCodeStyleSettings"        = XML (JetCodeStyleSettings)
 *         │     ├── "indentOptions"                  = XML (IndentOptions)
 *         │     └── "kotlinCommonCodeStyleSettings"  = XML (KotlinCommonCodeStyleSettings)
 *         └── …
 * </pre>
 *
 * <p>Scheme names may contain arbitrary Unicode characters (including spaces).
 * Forward slashes are percent-encoded when used as Preferences node names because
 * the Preferences API treats slashes as path separators.
 */
object KotlinSchemeManager {

    private val LOG = Logger.getLogger(KotlinSchemeManager::class.java.name)

    private const val KEY_NAME           = "name"
    private const val KEY_BASE_PRESET_ID = "basePresetId"
    private const val SCHEMES_NODE       = "customSchemes"

    /** Returns the root [Preferences] node that holds all custom scheme child nodes. */
    private fun customSchemesRoot(): Preferences =
        NbPreferences.forModule(KotlinSchemeManager::class.java).node(SCHEMES_NODE)

    /**
     * Encodes a scheme name for use as a [Preferences] node name.
     * Slashes are replaced because the Preferences API interprets them as path separators.
     */
    private fun encode(name: String): String = name.replace("/", "%2F")

    /** Decodes a Preferences node name back to the display scheme name. */
    private fun decode(nodeName: String): String = nodeName.replace("%2F", "/")

    /** Returns the [Preferences] node for the scheme with the given [name]. */
    private fun schemeNode(name: String): Preferences =
        customSchemesRoot().node(encode(name))

    // -------------------------------------------------------------------------
    // Listing and lookup
    // -------------------------------------------------------------------------

    /**
     * Returns an alphabetically sorted list of all custom scheme display names.
     *
     * @return sorted list of custom scheme names; empty if none exist
     */
    fun listCustomSchemeNames(): List<String> {
        val root = customSchemesRoot()
        return try {
            root.childrenNames()
                .map { decode(it) }
                .sorted()
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to list custom scheme names", e)
            emptyList()
        }
    }

    /**
     * Returns the base preset ID recorded when the scheme was created, or {@code null}
     * if the scheme was based on IDE defaults or if the scheme does not exist.
     *
     * @param name the display name of the custom scheme
     * @return preset ID string (e.g. {@code "KOTLIN_OFFICIAL"}), or {@code null}
     */
    fun getBasePresetId(name: String): String? =
        schemeNode(name).get(KEY_BASE_PRESET_ID, null)

    // -------------------------------------------------------------------------
    // Load / Save
    // -------------------------------------------------------------------------

    /**
     * Loads the custom scheme with the given [name] into a fresh [CodeStyleSettings] instance.
     *
     * @param name the display name of the custom scheme to load
     * @return a [CodeStyleSettings] populated from the stored scheme, or {@code null}
     *         if the scheme does not exist or fails to deserialize
     */
    fun loadScheme(name: String): CodeStyleSettings? {
        val root = customSchemesRoot()
        return try {
            if (!root.nodeExists(encode(name))) return null
            val node = schemeNode(name)
            val settings = CodeStyleSettings()
            KotlinCodeStylePreferences.load(node, settings)
            settings
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to load custom scheme '$name'", e)
            null
        }
    }

    /**
     * Creates a new custom scheme with the given [name], [settings], and optional
     * [basePresetId]. If a scheme with [name] already exists it is overwritten.
     *
     * @param name         the display name for the new scheme
     * @param settings     the code-style settings to snapshot
     * @param basePresetId the ID of the preset the scheme was duplicated from, or
     *                     {@code null} if based on IDE defaults
     */
    fun createScheme(name: String, settings: CodeStyleSettings, basePresetId: String?) {
        saveScheme(name, settings, basePresetId)
    }

    /**
     * Updates an existing custom scheme with new settings.
     * If the scheme does not exist, it is created (with no base preset).
     *
     * @param name     the display name of the scheme to update
     * @param settings the new code-style settings to persist
     */
    fun updateScheme(name: String, settings: CodeStyleSettings) {
        val existing = schemeNode(name).get(KEY_BASE_PRESET_ID, null)
        saveScheme(name, settings, existing)
    }

    private fun saveScheme(name: String, settings: CodeStyleSettings, basePresetId: String?) {
        try {
            val node = schemeNode(name)
            node.put(KEY_NAME, name)
            if (basePresetId != null) node.put(KEY_BASE_PRESET_ID, basePresetId)
            else node.remove(KEY_BASE_PRESET_ID)
            KotlinCodeStylePreferences.save(settings, node)
            node.flush()
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to save custom scheme '$name'", e)
        }
    }

    // -------------------------------------------------------------------------
    // Delete / Rename
    // -------------------------------------------------------------------------

    /**
     * Deletes the custom scheme with the given [name].
     * Does nothing if the scheme does not exist.
     *
     * @param name the display name of the scheme to delete
     */
    fun deleteScheme(name: String) {
        try {
            val root = customSchemesRoot()
            if (root.nodeExists(encode(name))) {
                schemeNode(name).removeNode()
                root.flush()
            }
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to delete custom scheme '$name'", e)
        }
    }

    /**
     * Renames a custom scheme from [oldName] to [newName].
     * The settings and base-preset ID are preserved. Does nothing if [oldName] does not exist.
     *
     * @param oldName the current display name of the scheme
     * @param newName the desired new display name
     */
    fun renameScheme(oldName: String, newName: String) {
        if (oldName == newName) return
        val settings = loadScheme(oldName) ?: return
        val basePresetId = getBasePresetId(oldName)
        createScheme(newName, settings, basePresetId)
        deleteScheme(oldName)
    }

    // -------------------------------------------------------------------------
    // Export / Import
    // -------------------------------------------------------------------------

    /**
     * Exports a custom scheme to an IDEA-compatible XML file.
     *
     * <p>The [file] content uses the {@code <code_scheme name="..." version="173">}
     * wrapper format, readable by IntelliJ IDEA.
     *
     * @param name     the display name written into the XML {@code name} attribute
     * @param settings the code-style settings to export
     * @param file     the destination file; created or overwritten
     */
    fun exportToXml(name: String, settings: CodeStyleSettings, file: File) {
        ensureKotlinProviderRegistered(settings)
        val cs = settings.getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings
        val xml = KotlinCodeStyleSerializer.exportSchemeXml(name, settings, cs)
        file.writeText(xml, Charsets.UTF_8)
    }

    /**
     * Imports a scheme from an IDEA-compatible XML file.
     *
     * <p>The scheme name is read from the XML {@code name} attribute.
     * Both files exported by [exportToXml] and files exported from IntelliJ IDEA
     * are accepted. Unknown fields are silently ignored.
     *
     * @param file the source XML file
     * @return a [Pair] of (display name, [CodeStyleSettings]) if the file is valid,
     *         or {@code null} on parse failure
     */
    fun importFromXml(file: File): Pair<String, CodeStyleSettings>? {
        return try {
            val xml = file.readText(Charsets.UTF_8)
            val settings = CodeStyleSettings()
            ensureKotlinProviderRegistered(settings)
            val cs = settings.getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings
            val outName = arrayOf("")
            KotlinCodeStyleSerializer.importSchemeXml(xml, outName, settings, cs)
            val name = outName[0].ifBlank { file.nameWithoutExtension }
            Pair(name, settings)
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to import scheme from ${file.path}", e)
            null
        }
    }

    private fun ensureKotlinProviderRegistered(settings: CodeStyleSettings) {
        if (settings.getCommonSettings(KotlinLanguage.INSTANCE) !is KotlinCommonCodeStyleSettings) {
            KotlinFormatterUtils.registerKotlinProvider(settings)
        }
    }
}
