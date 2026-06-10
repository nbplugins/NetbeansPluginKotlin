/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o. and contributors.
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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.formatting.options

import org.netbeans.api.editor.mimelookup.MimeLookup
import org.netbeans.api.editor.mimelookup.MimePath
import org.netbeans.api.editor.settings.SimpleValueNames
import org.netbeans.api.project.FileOwnerQuery
import org.netbeans.api.project.Project
import org.netbeans.api.project.ui.OpenProjects
import org.netbeans.modules.editor.NbEditorUtilities
import org.netbeans.modules.editor.indent.spi.CodeStylePreferences
import org.netbeans.modules.parsing.api.Source
import org.openide.filesystems.FileObject
import org.openide.loaders.DataObject
import org.openide.windows.TopComponent
import org.openide.windows.WindowManager
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import javax.swing.text.Document

/**
 * [CodeStylePreferences.Provider] for the `text/x-kotlin` MIME type.
 *
 * <p>NetBeans renders vertical indent-guide bars and computes Tab-key behaviour
 * from `IndentUtils.indentLevelSize(doc)` / `tabSize(doc)` / `isExpandTabs(doc)`,
 * each of which reads `indent-shift-width`, `tab-size`, `spaces-per-tab`, and
 * `expand-tabs` from `CodeStylePreferences.get(doc).getPreferences()`. Without
 * a provider registered for `text/x-kotlin`, those values fall back to
 * NetBeans defaults (4 spaces) — independent of the Kotlin formatter settings
 * the user configured in Tools&nbsp;&rarr;&nbsp;Options&nbsp;&rarr;&nbsp;Kotlin or
 * per project via {@code .idea/codeStyles/Project.xml}.
 *
 * <p>This provider closes that gap. It returns a {@link Preferences} view
 * backed by [ProjectCodeStyleStorage.getSettings] for the document/file's
 * owning project (with fallback to the global Kotlin settings when the project
 * has no per-project override).
 *
 * <p>Instances are cached per-project (and once for the global, project-less
 * case). When the user saves settings in either the global or per-project
 * panel, call [notifyChanged] so existing editors repaint indent guides and
 * the editor caches invalidate without the user having to close and reopen
 * the document.
 */
class KotlinCodeStylePreferencesProvider : CodeStylePreferences.Provider {

    /**
     * Resolves a [Preferences] view for the document being edited. The owning
     * [Project] is looked up via the document's [FileObject]; if no project
     * owns the file, the global view is returned.
     */
    override fun forDocument(doc: Document, mimeType: String): Preferences? {
        if (mimeType != KOTLIN_MIME) return null
        val fo: FileObject? = NbEditorUtilities.getFileObject(doc)
        val project = fo?.let { FileOwnerQuery.getOwner(it) }
        return preferencesFor(project)
    }

    /**
     * Resolves a [Preferences] view for the given file. The owning [Project] is
     * looked up via [FileOwnerQuery]; if no project owns the file, the global
     * view is returned.
     */
    override fun forFile(file: FileObject, mimeType: String): Preferences? {
        if (mimeType != KOTLIN_MIME) return null
        val project = FileOwnerQuery.getOwner(file)
        return preferencesFor(project)
    }

    companion object {

        /** MIME type handled by this provider. */
        const val KOTLIN_MIME: String = "text/x-kotlin"

        /** Sentinel key for the "no project" (global) preferences view. */
        private val GLOBAL_KEY = Any()

        /**
         * Cache of preferences views, one per owning project plus one for the
         * global view. A live cache (rather than per-call construction) is
         * required so that [notifyChanged] can target existing instances and
         * fire `PreferenceChangeEvent` to NetBeans' subscribed listeners.
         */
        private val cache: MutableMap<Any, KotlinIndentPreferences> = ConcurrentHashMap()

        /**
         * Returns (and lazily creates) the [Preferences] instance for the given
         * project, or the global view when [project] is `null`.
         */
        fun preferencesFor(project: Project?): Preferences {
            val key: Any = project ?: GLOBAL_KEY
            return cache.computeIfAbsent(key) { KotlinIndentPreferences(project) }
        }

        /**
         * Refreshes the preferences view(s) affected by a settings save.
         *
         * <p>When [project] is non-null, only that project's view is refreshed
         * — the user changed a per-project override.
         *
         * <p>When [project] is `null`, every cached view is refreshed: the
         * global view, plus every per-project view (because projects without a
         * `.idea/codeStyles/Project.xml` inherit the global indent options, and
         * even projects with overrides may now compare differently against the
         * new global state in future iterations).
         *
         * <p>Refreshing fires `PreferenceChangeEvent` to NetBeans' editor
         * listeners for every key whose value actually changed, which is what
         * triggers a repaint of the indent guides in any open document.
         */
        fun notifyChanged(project: Project?) {
            if (project != null) {
                cache[project]?.refresh()
                applyDocumentPropertiesForProject(project)
            } else {
                cache.values.forEach { it.refresh() }
                syncMimePreferences()
                // Re-stamp open .kt documents for every project that has a
                // per-project override — the MIME write only covers projects
                // without one, and any inheriting projects already pick up
                // the new MIME values automatically on the next paint.
                for (p in OpenProjects.getDefault().openProjects) {
                    if (ProjectCodeStyleStorage.getProjectSettings(p) != null) {
                        applyDocumentPropertiesForProject(p)
                    }
                }
            }
            // DocumentViewOp caches indentLevelSize/tabSize on its first paint and
            // only refreshes them from PreferenceChangeEvent on the MIME prefs.
            // Setting document properties alone is silent — fire a touch event on
            // the MIME node so every open .kt view re-reads doc properties (which
            // is what carries per-project values) and repaints indent guides.
            poketMimePreferences()
        }

        /** Writes the current `tab-size` value to itself to force a
         * `PreferenceChangeEvent` on the MIME node — `AbstractPreferences.put`
         * always enqueues the event regardless of whether the value changed,
         * which makes every listening `DocumentViewOp` re-read its settings
         * (including per-document `indent-shift-width` we just stamped). */
        private fun poketMimePreferences() {
            try {
                val mimePrefs = MimeLookup.getLookup(MimePath.parse(KOTLIN_MIME))
                    .lookup(Preferences::class.java) ?: return
                val current = mimePrefs.getInt(SimpleValueNames.TAB_SIZE, 4)
                mimePrefs.putInt(SimpleValueNames.TAB_SIZE, current)
            } catch (e: Exception) {
                Logger.getLogger(KotlinCodeStylePreferencesProvider::class.java.name)
                    .log(Level.WARNING, "Failed to poke Kotlin MIME preferences", e)
            }
        }

        /**
         * Stamps the four indent keys as document properties on every open
         * `.kt` document owned by [project], so that NB 30's `DocumentViewOp`
         * indent-guide renderer (which checks the document property *before*
         * falling back to MIME `Preferences`) reflects this project's
         * per-project override rather than the MIME-wide global value.
         */
        fun applyDocumentPropertiesForProject(project: Project) {
            val opts = ProjectCodeStyleStorage.getSettings(project).indentOptions
            for (fo in openKotlinFilesForProject(project)) {
                val doc = runCatching { Source.create(fo)?.getDocument(false) }.getOrNull() ?: continue
                applyDocumentProperties(doc, opts.INDENT_SIZE, opts.TAB_SIZE, !opts.USE_TAB_CHARACTER)
            }
        }

        /**
         * Stamps document properties on a single Kotlin document. Used by
         * the document-open hook in `KotlinInstaller` so that newly opened
         * `.kt` files immediately use the effective Kotlin indent for guide
         * rendering, before the user has changed any setting.
         */
        fun applyDocumentPropertiesForFile(file: FileObject) {
            val project = FileOwnerQuery.getOwner(file) ?: return
            // Only stamp when the project has its own override; without one,
            // MIME prefs already supply the correct (global) values and the
            // extra stamp is redundant.
            if (ProjectCodeStyleStorage.getProjectSettings(project) == null) return
            val doc = runCatching { Source.create(file)?.getDocument(false) }.getOrNull() ?: return
            val opts = ProjectCodeStyleStorage.getSettings(project).indentOptions
            applyDocumentProperties(doc, opts.INDENT_SIZE, opts.TAB_SIZE, !opts.USE_TAB_CHARACTER)
        }

        private fun applyDocumentProperties(doc: Document, indentSize: Int, tabSize: Int, expandTabs: Boolean) {
            doc.putProperty(SimpleValueNames.INDENT_SHIFT_WIDTH, indentSize)
            doc.putProperty(SimpleValueNames.TAB_SIZE, tabSize)
            doc.putProperty(SimpleValueNames.SPACES_PER_TAB, tabSize)
            doc.putProperty(SimpleValueNames.EXPAND_TABS, expandTabs)
        }

        private fun openKotlinFilesForProject(project: Project): List<FileObject> {
            val wm = WindowManager.getDefault() ?: return emptyList()
            return wm.registry.opened
                .filterIsInstance<TopComponent>()
                .mapNotNull { it.lookup?.lookup(DataObject::class.java)?.primaryFile }
                .filter { it.mimeType == KOTLIN_MIME && FileOwnerQuery.getOwner(it) == project }
        }

        /**
         * Writes the four indent keys into the standard MIME `Preferences` node
         * for `text/x-kotlin` so that NetBeans subsystems that read them via
         * `MimeLookup.getLookup(mime).lookup(Preferences.class)` — most notably
         * NB 30's `DocumentViewOp` indent-guide renderer — pick up Kotlin's
         * configured indent without the user having to reopen the document.
         *
         * <p>Only invoked for global settings changes: project-level overrides
         * cannot be expressed in the MIME-wide node (it's shared across all
         * Kotlin files regardless of owning project).
         */
        private fun syncMimePreferences() {
            try {
                val mimePrefs = MimeLookup.getLookup(MimePath.parse(KOTLIN_MIME))
                    .lookup(Preferences::class.java) ?: return
                val opts = ProjectCodeStyleStorage.getSettings(null).indentOptions
                mimePrefs.putInt(SimpleValueNames.INDENT_SHIFT_WIDTH, opts.INDENT_SIZE)
                mimePrefs.putInt(SimpleValueNames.TAB_SIZE, opts.TAB_SIZE)
                mimePrefs.putInt(SimpleValueNames.SPACES_PER_TAB, opts.TAB_SIZE)
                mimePrefs.putBoolean(SimpleValueNames.EXPAND_TABS, !opts.USE_TAB_CHARACTER)
                mimePrefs.flush()
            } catch (e: Exception) {
                Logger.getLogger(KotlinCodeStylePreferencesProvider::class.java.name)
                    .log(Level.WARNING, "Failed to sync Kotlin indent settings into MIME preferences", e)
            }
        }
    }
}

/**
 * In-memory [AbstractPreferences] root that mirrors the effective Kotlin
 * [com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions] for a
 * given project (or the global Kotlin settings when [project] is `null`).
 *
 * <p>Only the four NetBeans editor keys consumed by `IndentUtils` are exposed:
 * `indent-shift-width`, `tab-size`, `spaces-per-tab`, `expand-tabs`. Each call
 * to [refresh] re-reads the effective Kotlin settings and fires a
 * `PreferenceChangeEvent` for every key whose value changed, which is how the
 * NetBeans editor invalidates its cached indent sizes and repaints indent
 * guides without requiring the document to be closed and reopened.
 */
internal class KotlinIndentPreferences(private val project: Project?)
    : AbstractPreferences(null, "") {

    private val map: MutableMap<String, String> = ConcurrentHashMap()

    init {
        refresh()
    }

    /**
     * Re-reads [com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions]
     * from the effective Kotlin settings for [project] and applies any changes
     * via [put], so that listeners are notified for keys whose value actually
     * differs from the previous snapshot.
     */
    fun refresh() {
        val settings = ProjectCodeStyleStorage.getSettings(project)
        val opts = settings.indentOptions
        putIfChanged(SimpleValueNames.INDENT_SHIFT_WIDTH, opts.INDENT_SIZE.toString())
        putIfChanged(SimpleValueNames.TAB_SIZE, opts.TAB_SIZE.toString())
        putIfChanged(SimpleValueNames.SPACES_PER_TAB, opts.TAB_SIZE.toString())
        putIfChanged(SimpleValueNames.EXPAND_TABS, (!opts.USE_TAB_CHARACTER).toString())
    }

    private fun putIfChanged(key: String, value: String) {
        if (map[key] != value) {
            put(key, value)
        }
    }

    override fun putSpi(key: String, value: String) {
        map[key] = value
    }

    override fun getSpi(key: String): String? = map[key]

    override fun removeSpi(key: String) {
        map.remove(key)
    }

    override fun keysSpi(): Array<String> = map.keys.toTypedArray()

    override fun childrenNamesSpi(): Array<String> = emptyArray()

    override fun childSpi(name: String): AbstractPreferences =
        throw UnsupportedOperationException("Kotlin indent preferences have no child nodes")

    override fun syncSpi() {}

    override fun flushSpi() {}

    override fun removeNodeSpi() {
        throw UnsupportedOperationException("Kotlin indent preferences root cannot be removed")
    }
}
