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

import org.jetbrains.kotlin.tools.projectWizard.projectTemplates.ConsoleApplicationProjectTemplateWithSample
import org.netbeans.junit.NbTestCase

/**
 * Tests for [ProjectVariant].
 */
class ProjectVariantTest : NbTestCase("ProjectVariantTest") {

    /** Only SCRIPT contains a generated script, and it lives under `src/main/kotlin`. */
    fun testScriptRelativePath() {
        assertEquals("src/main/kotlin/main.kts", ProjectVariant.SCRIPT.scriptRelativePath)
        assertNull(ProjectVariant.APPLICATION.scriptRelativePath)
        assertNull(ProjectVariant.LIBRARY.scriptRelativePath)
    }

    /** The application variant reuses the upstream console-application template. */
    fun testApplicationUsesUpstreamTemplate() {
        assertSame(ConsoleApplicationProjectTemplateWithSample, ProjectVariant.APPLICATION.template())
    }

    /** Library and script share the local library template, without sample code. */
    fun testLibraryAndScriptUseLibraryTemplate() {
        assertSame(KotlinLibraryProjectTemplate, ProjectVariant.LIBRARY.template())
        assertSame(KotlinLibraryProjectTemplate, ProjectVariant.SCRIPT.template())
    }
}
