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
package io.github.nbplugins.kotlin.nbm.formatting.options

import com.intellij.psi.codeStyle.CodeStyleSettings
import io.github.nbplugins.kotlin.formatter.KotlinCodeStyleSerializer
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.formatter.KotlinCommonCodeStyleSettings
import org.netbeans.api.project.Project
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Per-project Kotlin code-style settings cache.
 *
 * <p>Settings are read from disk exactly once (when a project is opened or when
 * project/global settings are explicitly saved) and stored in an in-memory cache.
 * At format time, [getSettings] performs a cheap cache lookup with no I/O.
 *
 * <p>On-disk storage uses IntelliJ IDEA's {@code .idea/codeStyles/} format so
 * the files can be committed to version control and read by both IDEs:
 * <ul>
 *   <li>{@code .idea/codeStyles/codeStyleConfig.xml} — toggle flag</li>
 *   <li>{@code .idea/codeStyles/Project.xml} — full settings in
 *       {@code <component name="ProjectCodeStyleConfiguration">} envelope</li>
 * </ul>
 */
object ProjectCodeStyleStorage {

    private val LOG = Logger.getLogger(ProjectCodeStyleStorage::class.java.name)

    /** File name for the per-project settings. */
    private const val PROJECT_XML = "Project.xml"

    /** File name for the per-project toggle flag. */
    private const val CONFIG_XML = "codeStyleConfig.xml"

    /** Relative path inside the project directory. */
    private const val IDEA_CODE_STYLES = ".idea/codeStyles"

    /** XML root element name used by IntelliJ IDEA. */
    private const val COMPONENT_NAME = "ProjectCodeStyleConfiguration"

    /** Cached per-project settings. Absent entry means "use global settings". */
    private val cache: MutableMap<Project, CodeStyleSettings> = ConcurrentHashMap()

    // -------------------------------------------------------------------------
    // Lifecycle events — called once per project open / close / settings save
    // -------------------------------------------------------------------------

    /**
     * Called when a project is opened. Reads {@code .idea/codeStyles/Project.xml}
     * from disk (once) and stores the result in the cache. No-op if the file does
     * not exist or cannot be parsed.
     *
     * @param project the project that was opened
     */
    fun onProjectOpened(project: Project) {
        val settings = loadFromFile(project)
        if (settings != null) {
            cache[project] = settings
        }
    }

    /**
     * Called when a project is closed. Removes the cached settings to free memory.
     *
     * @param project the project that was closed
     */
    fun onProjectClosed(project: Project) {
        cache.remove(project)
    }

    /**
     * Called by [KotlinProjectOptionsPanelController] when the user saves
     * project-specific settings (i.e. the panel state differs from the global IDE
     * settings). Writes both XML files and updates the cache.
     *
     * @param project  the project whose settings are being saved
     * @param settings the settings to persist
     */
    fun onProjectSettingsSaved(project: Project, settings: CodeStyleSettings) {
        writeFiles(project, settings)
        cache[project] = cloneSettings(settings)
    }

    /**
     * Called by [KotlinProjectOptionsPanelController] when the user resets the
     * project settings to the global IDE settings (i.e. the panel state matches
     * the global). Deletes the on-disk files and removes the cache entry.
     *
     * @param project the project whose settings are being cleared
     */
    fun onProjectSettingsCleared(project: Project) {
        deleteFiles(project)
        cache.remove(project)
    }

    /**
     * Called by [KotlinOptionsPanelController] after the global IDE settings are
     * saved. Projects that have no project-level override are not affected (they
     * always fall through to the global singleton at format time). This method
     * exists as an explicit notification point for future invalidation logic.
     */
    fun onGlobalSettingsChanged() {
        // Projects with per-project files are unaffected: their cache entries
        // continue to override the global singleton.
        // Projects without per-project files fall through to KotlinFormatterUtils.getSettings()
        // which is already updated by KotlinOptionsPanelController.applyChanges().
    }

    // -------------------------------------------------------------------------
    // Formatter lookup — no I/O, called on every format/indent operation
    // -------------------------------------------------------------------------

    /**
     * Returns the cached per-project [CodeStyleSettings] for [project], or {@code null}
     * if the project has no override (i.e. it inherits the global IDE settings).
     * Used by [KotlinProjectOptionsPanelController] to seed the project panel on load.
     *
     * @param project the project to look up
     * @return cached per-project settings, or {@code null}
     */
    fun getProjectSettings(project: Project): CodeStyleSettings? = cache[project]

    /**
     * Returns the effective [CodeStyleSettings] for the given [project].
     *
     * <p>If the project has a per-project override in the cache, those settings
     * are returned. Otherwise the global formatter settings singleton is returned.
     * This method performs no disk I/O.
     *
     * @param project the owning project of the file being formatted, or {@code null}
     * @return per-project settings if available; global settings otherwise
     */
    fun getSettings(project: Project?): CodeStyleSettings =
        (project?.let { cache[it] }) ?: KotlinFormatterUtils.getSettings()

    // -------------------------------------------------------------------------
    // Comparison — used by the controller to decide whether to write files
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if [settings] is field-identical to the current global
     * IDE settings. Used by [KotlinProjectOptionsPanelController] to decide whether
     * to write or delete the per-project files on OK.
     *
     * @param settings the settings snapshot from the project panel
     * @return {@code true} if no effective per-project override exists
     */
    fun settingsMatchGlobal(settings: CodeStyleSettings): Boolean {
        val global = CodeStyleSettings()
        ensureKotlinProviderRegistered(global)
        KotlinCodeStylePreferences.load(KotlinCodeStylePreferences.prefs(), global)
        val result = xmlEquals(settings, global)
        return result
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Reads per-project settings from {@code .idea/codeStyles/Project.xml}.
     * Returns {@code null} if the file does not exist or parsing fails.
     */
    private fun loadFromFile(project: Project): CodeStyleSettings? {
        val projectXml = ideaCodeStylesDir(project)?.let { File(it, PROJECT_XML) } ?: return null
        if (!projectXml.exists()) return null
        return try {
            val xml = projectXml.readText(Charsets.UTF_8)
            // Strip the outer <component name="ProjectCodeStyleConfiguration"> envelope.
            val innerXml = extractCodeSchemeXml(xml) ?: return null
            val settings = CodeStyleSettings()
            ensureKotlinProviderRegistered(settings)
            val cs = settings.getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings
            val outName = arrayOf("")
            KotlinCodeStyleSerializer.importSchemeXml(innerXml, outName, settings, cs)
            settings
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to load per-project Kotlin code style from ${projectXml.path}", e)
            null
        }
    }

    /**
     * Writes {@code Project.xml} and {@code codeStyleConfig.xml} into
     * {@code .idea/codeStyles/}, creating the directory if needed.
     */
    private fun writeFiles(project: Project, settings: CodeStyleSettings) {
        val dir = ideaCodeStylesDir(project) ?: run {
            LOG.warning("Cannot resolve project directory for $project; skipping project settings write")
            return
        }
        try {
            dir.mkdirs()
            ensureKotlinProviderRegistered(settings)
            val cs = settings.getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings
            val innerXml = KotlinCodeStyleSerializer.exportSchemeXml("Project", settings, cs)
            val projectXml = wrapWithComponentEnvelope(innerXml)
            File(dir, PROJECT_XML).writeText(projectXml, Charsets.UTF_8)
            File(dir, CONFIG_XML).writeText(configXml(), Charsets.UTF_8)
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to write per-project Kotlin code style for $project", e)
        }
    }

    /**
     * Deletes {@code Project.xml} and {@code codeStyleConfig.xml} from
     * {@code .idea/codeStyles/}. Silently ignores missing files.
     */
    private fun deleteFiles(project: Project) {
        val dir = ideaCodeStylesDir(project) ?: return
        try {
            File(dir, PROJECT_XML).delete()
            File(dir, CONFIG_XML).delete()
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to delete per-project Kotlin code style for $project", e)
        }
    }

    /**
     * Resolves the {@code .idea/codeStyles/} directory for [project] as a
     * {@link java.io.File}. Returns {@code null} if the project directory cannot
     * be determined.
     */
    private fun ideaCodeStylesDir(project: Project): File? {
        val projectDir: FileObject = project.projectDirectory ?: return null
        return File(FileUtil.toFile(projectDir), IDEA_CODE_STYLES)
    }

    /**
     * Extracts the inner {@code <code_scheme ...>} XML string from the IDEA
     * {@code <component name="ProjectCodeStyleConfiguration">} envelope.
     * Returns {@code null} if the envelope is not found.
     */
    private fun extractCodeSchemeXml(componentXml: String): String? {
        // Fast string-based extraction to avoid a full DOM parse just for the envelope.
        val startTag = "<code_scheme"
        val endTag = "</code_scheme>"
        val start = componentXml.indexOf(startTag)
        val end = componentXml.lastIndexOf(endTag)
        if (start < 0 || end < 0 || end <= start) return null
        return componentXml.substring(start, end + endTag.length)
    }

    /**
     * Wraps the inner {@code <code_scheme>} XML in the IDEA
     * {@code <component name="ProjectCodeStyleConfiguration">} envelope.
     *
     * @param codeSchemeXml the inner block produced by [KotlinCodeStyleSerializer.exportSchemeXml]
     * @return the complete {@code Project.xml} content
     */
    private fun wrapWithComponentEnvelope(codeSchemeXml: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<component name="$COMPONENT_NAME">
$codeSchemeXml
</component>
"""

    /**
     * Returns the content of {@code codeStyleConfig.xml} that enables per-project
     * settings in IntelliJ IDEA.
     */
    private fun configXml(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<component name="$COMPONENT_NAME">
  <state>
    <option name="USE_PER_PROJECT_SETTINGS" value="true" />
  </state>
</component>
"""

    /**
     * Compares two [CodeStyleSettings] instances by serializing both to the same
     * XML format and comparing strings. This correctly handles all fields including
     * those not editable in any panel tab.
     */
    private fun xmlEquals(a: CodeStyleSettings, b: CodeStyleSettings): Boolean {
        return try {
            ensureKotlinProviderRegistered(a)
            ensureKotlinProviderRegistered(b)
            val csA = a.getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings
            val csB = b.getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings
            KotlinCodeStyleSerializer.exportSchemeXml("cmp", a, csA) ==
                KotlinCodeStyleSerializer.exportSchemeXml("cmp", b, csB)
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to compare code style settings", e)
            false
        }
    }

    /**
     * Creates a deep copy of [settings] by round-tripping through the XML serializer.
     * Used to store a snapshot in the cache that is independent of the source object.
     */
    private fun cloneSettings(settings: CodeStyleSettings): CodeStyleSettings {
        val clone = CodeStyleSettings()
        ensureKotlinProviderRegistered(clone)
        val cs = clone.getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings
        try {
            val outName = arrayOf("")
            KotlinCodeStyleSerializer.importSchemeXml(
                KotlinCodeStyleSerializer.exportSchemeXml("clone", settings,
                    settings.getCommonSettings(KotlinLanguage.INSTANCE) as? KotlinCommonCodeStyleSettings),
                outName, clone, cs
            )
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to clone CodeStyleSettings; using original", e)
            return settings
        }
        return clone
    }

    private fun ensureKotlinProviderRegistered(settings: CodeStyleSettings) {
        if (settings.getCommonSettings(KotlinLanguage.INSTANCE) !is KotlinCommonCodeStyleSettings) {
            KotlinFormatterUtils.registerKotlinProvider(settings)
        }
    }
}
