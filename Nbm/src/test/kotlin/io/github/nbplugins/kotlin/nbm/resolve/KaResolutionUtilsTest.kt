/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.resolve

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaResolutionUtils].
 *
 * All tests require a K2-session-owned [KtFile] from [KotlinAnalysisAPISession].
 */
class KaResolutionUtilsTest : KotlinTestCase("KaResolutionUtils", "diagnostics") {

    /**
     * Verifies that [KaResolutionUtils.getResolutionScope] returns a non-null composite
     * [org.jetbrains.kotlin.analysis.api.scopes.KaScope] for an element inside a Kotlin file.
     */
    fun testGetResolutionScope_returnsNonNullScope() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        assertNotNull("Session must have at least one source file", ktFile)

        analyze(ktFile!!) {
            with(KaResolutionUtils) {
                val scope = getResolutionScope(ktFile!!)
                assertNotNull("getResolutionScope must return a non-null KaScope", scope)
            }
        }
    }
}
