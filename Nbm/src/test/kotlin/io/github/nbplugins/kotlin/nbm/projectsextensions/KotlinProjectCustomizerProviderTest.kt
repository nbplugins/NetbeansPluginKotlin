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
package io.github.nbplugins.kotlin.nbm.projectsextensions

import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.netbeans.api.project.Project
import org.netbeans.junit.NbTestCase
import org.netbeans.spi.project.ui.support.ProjectCustomizer
import org.openide.filesystems.FileUtil
import org.openide.util.Lookup
import java.io.File
import java.nio.file.Files

/**
 * Tests for [KotlinProjectCustomizerProvider].
 *
 * <p>Verifies that the provider creates the correct category and panel without
 * requiring a live NetBeans project type.
 */
class KotlinProjectCustomizerProviderTest : NbTestCase("KotlinProjectCustomizerProviderTest") {

    private lateinit var tempDir: File
    private lateinit var project: Project
    private val provider = KotlinProjectCustomizerProvider()

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
        tempDir = Files.createTempDirectory("customizer-provider-test").toFile()
        val rootFo = FileUtil.toFileObject(tempDir)
        project = object : Project {
            override fun getProjectDirectory() = rootFo
            override fun getLookup(): Lookup = Lookup.EMPTY
        }
        ProjectCodeStyleStorage.onProjectClosed(project)
    }

    override fun tearDown() {
        ProjectCodeStyleStorage.onProjectClosed(project)
        tempDir.deleteRecursively()
        super.tearDown()
    }

    /** [KotlinProjectCustomizerProvider.createCategory] returns a non-null category. */
    fun testCreateCategory_returnsNonNull() {
        val category = provider.createCategory(Lookup.EMPTY)
        assertNotNull("category should not be null", category)
    }

    /** Category display name is "Kotlin Formatter". */
    fun testCreateCategory_displayName() {
        val category = provider.createCategory(Lookup.EMPTY)
        assertEquals("Kotlin Formatter", category.displayName)
    }

    /** [KotlinProjectCustomizerProvider.createComponent] with no project in lookup returns empty component. */
    fun testCreateComponent_noProject_returnsEmptyComponent() {
        val category = provider.createCategory(Lookup.EMPTY)
        val component = provider.createComponent(category, Lookup.EMPTY)
        assertNotNull("component should not be null even without project", component)
    }

    /** [KotlinProjectCustomizerProvider.createComponent] with project in lookup returns non-null component. */
    fun testCreateComponent_withProject_returnsComponent() {
        val lookup = org.openide.util.lookup.Lookups.singleton(project)
        val category = provider.createCategory(lookup)
        val component = provider.createComponent(category, lookup)
        assertNotNull("component should not be null with project", component)
    }

    /** createComponent() with project does not throw during load. */
    fun testCreateComponent_withProject_doesNotThrow() {
        val lookup = org.openide.util.lookup.Lookups.singleton(project)
        val category = provider.createCategory(lookup)
        // Must complete without throwing (load seeds from global prefs, not from PSI).
        val component = provider.createComponent(category, lookup)
        assertNotNull(component)
    }
}
