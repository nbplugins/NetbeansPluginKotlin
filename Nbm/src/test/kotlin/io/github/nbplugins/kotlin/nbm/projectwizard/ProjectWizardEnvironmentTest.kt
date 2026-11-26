/*******************************************************************************
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
package io.github.nbplugins.kotlin.nbm.projectwizard

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.tools.projectWizard.Versions
import org.jetbrains.kotlin.tools.projectWizard.compatibility.DependencyVersionStore
import org.jetbrains.kotlin.tools.projectWizard.compatibility.KotlinWizardVersionStore
import org.jetbrains.kotlin.tools.projectWizard.plugins.AndroidSdkProvider
import org.jetbrains.kotlin.tools.projectWizard.plugins.projectTemplates.ProjectTemplatesProvider
import org.netbeans.junit.NbTestCase

/**
 * Tests for [ProjectWizardEnvironment].
 */
class ProjectWizardEnvironmentTest : NbTestCase("ProjectWizardEnvironmentTest") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
        ProjectWizardEnvironment.ensureRegistered()
    }

    /** The injected store must be the source of the generated Kotlin version. */
    fun testKotlinVersionIsInjected() {
        assertEquals("2.3.21", Versions.KOTLIN.text)
        assertEquals(GENERATED_KOTLIN_VERSION, Versions.KOTLIN.text)
    }

    /** The Kotlin wizard version store must be registered and hold the injected state. */
    fun testKotlinWizardVersionStoreIsRegistered() {
        val store = KotlinWizardVersionStore.getInstance()
        assertNotNull("KotlinWizardVersionStore must be registered", store)
        assertEquals(GENERATED_KOTLIN_VERSION, store.state?.kotlinPluginVersion)
        assertFalse("injected state must not be marked as default", store.state?.isDefault ?: true)
    }

    /** The dependency store must be registered, otherwise `Dependencies` cannot be loaded. */
    fun testDependencyVersionStoreIsRegistered() {
        assertNotNull(DependencyVersionStore.getInstance())
    }

    /** Registering twice must not fail or overwrite the injected state. */
    fun testEnsureRegisteredIsIdempotent() {
        ProjectWizardEnvironment.ensureRegistered()
        ProjectWizardEnvironment.ensureRegistered()
        assertEquals(GENERATED_KOTLIN_VERSION, Versions.KOTLIN.text)
    }

    /** Both extension points read while initializing wizard settings must be registered. */
    fun testWizardExtensionPointsAreRegistered() {
        assertNotNull(AndroidSdkProvider.EP_NAME.name)
        assertEquals(0, AndroidSdkProvider.EP_NAME.extensions.size)
        assertNotNull(ProjectTemplatesProvider.EP_NAME.name)
        assertEquals(0, ProjectTemplatesProvider.EP_NAME.extensions.size)
    }
}
