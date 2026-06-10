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
package io.github.nbplugins.kotlin.nbm.options

import io.github.nbplugins.kotlin.nbm.formatting.options.KotlinCodeStylePreferences
import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage
import org.netbeans.api.project.Project
import java.awt.BorderLayout
import java.util.prefs.Preferences
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Controller for the "Kotlin Formatter" category in the Project Properties dialog.
 *
 * <p>Embeds [KotlinOptionsPanel] unchanged — giving the project panel an identical
 * look and behaviour to Tools&nbsp;&rarr;&nbsp;Options&nbsp;&rarr;&nbsp;Kotlin — plus a
 * "Reset to IDE settings" button at the top.
 *
 * <p>An isolated temporary [Preferences] node is used as the panel's backing store so
 * that the scheme bar's "Apply" action never touches the global IDE preferences. On
 * [load] the node is seeded from the project's cached settings (if a per-project file
 * exists) or from the current global IDE preferences. On [applyChanges] the panel's
 * in-memory state is compared against the global settings and either a project file is
 * written (settings differ) or the project file is deleted (settings match global).
 *
 * <p>Cancelling the dialog leaves no files written or deleted; the temporary node is
 * simply discarded.
 *
 * @param project the project whose formatter settings are being edited
 */
class KotlinProjectOptionsPanelController(private val project: Project) {

    /**
     * Isolated in-memory preferences node backing the project panel. A unique path
     * prevents collisions between concurrent Project Properties dialogs. Global IDE
     * preferences are never touched by this node.
     */
    private val tempPrefs: Preferences =
        Preferences.userRoot().node("kotlin-project-settings-${project.hashCode()}-${System.nanoTime()}")

    private val panel = KotlinOptionsPanel(
        onChange = {},
        persistPrefs = { tempPrefs }
    )

    /**
     * The root UI component to embed in the Project Properties dialog. Contains the
     * "Reset to IDE settings" button at the top and [panel] in the center.
     */
    val component: JComponent = JPanel(BorderLayout()).apply {
        add(JButton("Reset to IDE settings").apply {
            addActionListener { resetToIdeSettings() }
        }, BorderLayout.NORTH)
        add(panel, BorderLayout.CENTER)
    }

    /**
     * Populates the panel from the project's cached settings (if a per-project file
     * exists) or from the current global IDE preferences. Must be called before the
     * panel is shown.
     */
    fun load() {
        val projectSettings = ProjectCodeStyleStorage.getProjectSettings(project)
        if (projectSettings != null) {
            KotlinCodeStylePreferences.save(projectSettings, tempPrefs)
        } else {
            seedTempPrefsFromGlobal()
        }
        panel.load(tempPrefs)
    }

    /**
     * Persists the panel state when the user clicks OK in the Project Properties dialog.
     *
     * <p>Compares the panel's current [com.intellij.psi.codeStyle.CodeStyleSettings]
     * against the global IDE settings:
     * <ul>
     *   <li>If they differ, the project file is written and the cache is updated.</li>
     *   <li>If they match, any existing project file is deleted and the cache entry
     *       is removed, so the project falls back to the global IDE settings.</li>
     * </ul>
     */
    fun applyChanges() {
        val s = panel.currentSettings()
        if (ProjectCodeStyleStorage.settingsMatchGlobal(s)) {
            ProjectCodeStyleStorage.onProjectSettingsCleared(project)
        } else {
            ProjectCodeStyleStorage.onProjectSettingsSaved(project, s)
        }
    }

    /**
     * Called when the user cancels the Project Properties dialog. No files are written
     * or deleted; the temporary preferences node is simply discarded.
     */
    fun cancel() {
        /* no-op — in-memory only; no files were touched */
    }

    /**
     * Resets the panel to the current global IDE settings without writing any files.
     * Triggered by the "Reset to IDE settings" button.
     */
    private fun resetToIdeSettings() {
        seedTempPrefsFromGlobal()
        panel.load(tempPrefs)
    }

    /**
     * Copies all keys from the global IDE preferences node into [tempPrefs], replacing
     * any previously loaded project-specific values. Copying all keys (rather than a
     * fixed subset) ensures that the active scheme name (PREFS_KEY_CUSTOM_SCHEME) and
     * any sub-panel keys added in the future are always transferred correctly.
     */
    private fun seedTempPrefsFromGlobal() {
        val globalPrefs = KotlinCodeStylePreferences.prefs()
        for (key in globalPrefs.keys()) {
            val value = globalPrefs.get(key, null)
            if (value != null) tempPrefs.put(key, value) else tempPrefs.remove(key)
        }
    }
}
