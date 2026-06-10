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
import io.github.nbplugins.kotlin.nbm.formatting.KotlinFormatterUtils
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.idea.core.formatter.KotlinCodeStyleSettings
import org.netbeans.api.project.Project
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileUtil
import org.openide.util.Lookup
import java.io.File
import java.nio.file.Files

/**
 * Tests for [ProjectCodeStyleStorage].
 *
 * <p>Uses an anonymous [Project] stub backed by a real temporary directory so
 * that the file I/O paths under test exercise the actual file system without
 * requiring a fully bootstrapped NetBeans project.
 */
class ProjectCodeStyleStorageTest : NbTestCase("ProjectCodeStyleStorageTest") {

    private lateinit var tempDir: File
    private lateinit var project: Project

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
        tempDir = Files.createTempDirectory("project-code-style-test").toFile()
        val rootFo = FileUtil.toFileObject(tempDir)
        project = object : Project {
            override fun getProjectDirectory() = rootFo
            override fun getLookup(): Lookup = Lookup.EMPTY
        }
        // Clean cache entry from previous test runs if any.
        ProjectCodeStyleStorage.onProjectClosed(project)
    }

    override fun tearDown() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        tempDir.deleteRecursively()
        super.tearDown()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun ideaDir(): File = File(tempDir, ".idea/codeStyles")

    private fun makeSettings(indentSize: Int): CodeStyleSettings {
        val s = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(s)
        s.indentOptions.INDENT_SIZE = indentSize
        return s
    }

    // ── onProjectOpened ─────────────────────────────────────────────────────

    /** [ProjectCodeStyleStorage.onProjectOpened] is a no-op when no project file exists. */
    fun testOnProjectOpened_noFile_noCache() {
        ProjectCodeStyleStorage.onProjectOpened(project)
        assertNull(
            "cache should be empty when no .idea/codeStyles/Project.xml exists",
            ProjectCodeStyleStorage.getProjectSettings(project)
        )
    }

    /** [ProjectCodeStyleStorage.onProjectOpened] loads settings from the project file. */
    fun testOnProjectOpened_fileExists_populatesCache() {
        // Write a project file via the storage itself, then close and reopen.
        val saved = makeSettings(2)
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, saved)
        ProjectCodeStyleStorage.onProjectClosed(project)

        ProjectCodeStyleStorage.onProjectOpened(project)

        val loaded = ProjectCodeStyleStorage.getProjectSettings(project)
        assertNotNull("cache should contain project settings after onProjectOpened", loaded)
        assertEquals("indent size should survive file round-trip", 2, loaded!!.indentOptions.INDENT_SIZE)
    }

    // ── onProjectSettingsSaved ───────────────────────────────────────────────

    /** [ProjectCodeStyleStorage.onProjectSettingsSaved] writes both XML files. */
    fun testOnProjectSettingsSaved_writesBothFiles() {
        val settings = makeSettings(3)
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, settings)

        assertTrue("Project.xml must exist", File(ideaDir(), "Project.xml").exists())
        assertTrue("codeStyleConfig.xml must exist", File(ideaDir(), "codeStyleConfig.xml").exists())
    }

    /** [ProjectCodeStyleStorage.onProjectSettingsSaved] updates the in-memory cache. */
    fun testOnProjectSettingsSaved_updatesCache() {
        val settings = makeSettings(3)
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, settings)

        val cached = ProjectCodeStyleStorage.getProjectSettings(project)
        assertNotNull("cache should be populated after save", cached)
        assertEquals(3, cached!!.indentOptions.INDENT_SIZE)
    }

    /** Project.xml has the expected IDEA-compatible envelope. */
    fun testOnProjectSettingsSaved_projectXmlHasEnvelope() {
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, makeSettings(4))
        val xml = File(ideaDir(), "Project.xml").readText()
        assertTrue("Project.xml must have component envelope",
            xml.contains("<component name=\"ProjectCodeStyleConfiguration\">"))
        assertTrue("Project.xml must have code_scheme element",
            xml.contains("<code_scheme"))
    }

    // ── onProjectSettingsCleared ─────────────────────────────────────────────

    /** [ProjectCodeStyleStorage.onProjectSettingsCleared] deletes both files. */
    fun testOnProjectSettingsCleared_deletesFiles() {
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, makeSettings(2))
        assertTrue(File(ideaDir(), "Project.xml").exists())

        ProjectCodeStyleStorage.onProjectSettingsCleared(project)

        assertFalse("Project.xml should be deleted", File(ideaDir(), "Project.xml").exists())
        assertFalse("codeStyleConfig.xml should be deleted", File(ideaDir(), "codeStyleConfig.xml").exists())
    }

    /** [ProjectCodeStyleStorage.onProjectSettingsCleared] removes the cache entry. */
    fun testOnProjectSettingsCleared_removesCache() {
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, makeSettings(2))
        assertNotNull(ProjectCodeStyleStorage.getProjectSettings(project))

        ProjectCodeStyleStorage.onProjectSettingsCleared(project)

        assertNull("cache entry should be removed after clear", ProjectCodeStyleStorage.getProjectSettings(project))
    }

    /** Clearing without a prior save is a no-op. */
    fun testOnProjectSettingsCleared_noFile_noOp() {
        // Must not throw.
        ProjectCodeStyleStorage.onProjectSettingsCleared(project)
        assertNull(ProjectCodeStyleStorage.getProjectSettings(project))
    }

    // ── getSettings ──────────────────────────────────────────────────────────

    /** [ProjectCodeStyleStorage.getSettings] returns the project cache entry when present. */
    fun testGetSettings_projectOverride_returnsProjectSettings() {
        val saved = makeSettings(7)
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, saved)

        val result = ProjectCodeStyleStorage.getSettings(project)
        assertEquals("getSettings should return per-project settings when cached",
            7, result.indentOptions.INDENT_SIZE)
    }

    /** [ProjectCodeStyleStorage.getSettings] falls back to global settings for uncached project. */
    fun testGetSettings_noOverride_returnsGlobal() {
        val global = KotlinFormatterUtils.getSettings()
        val result = ProjectCodeStyleStorage.getSettings(project)
        assertSame("getSettings should return global singleton when no override", global, result)
    }

    /** [ProjectCodeStyleStorage.getSettings] returns global settings for null project. */
    fun testGetSettings_nullProject_returnsGlobal() {
        val global = KotlinFormatterUtils.getSettings()
        val result = ProjectCodeStyleStorage.getSettings(null)
        assertSame("getSettings(null) should return global singleton", global, result)
    }

    // ── settingsMatchGlobal ───────────────────────────────────────────────────

    /** [ProjectCodeStyleStorage.settingsMatchGlobal] returns true for unmodified fresh settings. */
    fun testSettingsMatchGlobal_freshSettings_true() {
        // Fresh settings loaded from global prefs should match global.
        val s = CodeStyleSettings()
        KotlinFormatterUtils.registerKotlinProvider(s)
        val prefs = KotlinCodeStylePreferences.prefs()
        KotlinCodeStylePreferences.load(prefs, s)
        assertTrue("fresh settings loaded from global prefs should match global",
            ProjectCodeStyleStorage.settingsMatchGlobal(s))
    }

    /** [ProjectCodeStyleStorage.settingsMatchGlobal] returns false when indent size differs. */
    fun testSettingsMatchGlobal_modifiedIndent_false() {
        val global = KotlinFormatterUtils.getSettings()
        val differentIndent = if (global.indentOptions.INDENT_SIZE == 4) 2 else 4
        val s = makeSettings(differentIndent)
        assertFalse("settings with different indent should not match global",
            ProjectCodeStyleStorage.settingsMatchGlobal(s))
    }

    // ── XML round-trip ────────────────────────────────────────────────────────

    /** Full XML round-trip: save → close → reopen → loaded settings match saved. */
    fun testXmlRoundTrip_indentSize() {
        val saved = makeSettings(3)
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, saved)
        ProjectCodeStyleStorage.onProjectClosed(project)

        ProjectCodeStyleStorage.onProjectOpened(project)
        val loaded = ProjectCodeStyleStorage.getProjectSettings(project)

        assertNotNull(loaded)
        assertEquals("indent size must survive XML round-trip", 3, loaded!!.indentOptions.INDENT_SIZE)
    }

    /** Full XML round-trip preserves [KotlinCodeStyleSettings] field. */
    fun testXmlRoundTrip_kotlinField() {
        val saved = makeSettings(4)
        saved.getCustomSettings(KotlinCodeStyleSettings::class.java).ALLOW_TRAILING_COMMA = true
        ProjectCodeStyleStorage.onProjectSettingsSaved(project, saved)
        ProjectCodeStyleStorage.onProjectClosed(project)

        ProjectCodeStyleStorage.onProjectOpened(project)
        val loaded = ProjectCodeStyleStorage.getProjectSettings(project)

        assertNotNull(loaded)
        assertTrue("ALLOW_TRAILING_COMMA must survive XML round-trip",
            loaded!!.getCustomSettings(KotlinCodeStyleSettings::class.java).ALLOW_TRAILING_COMMA)
    }
}
