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

import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaSuppressionCache].
 */
class KaSuppressionCacheTest : KotlinTestCase("KaSuppressionCache", "diagnostics") {

    /**
     * Verifies that [KaSuppressionCache.getAnnotationFqNames] returns an empty list
     * when [kaKtFile] is `null` (K2 session unavailable).
     */
    fun testGetAnnotationFqNames_returnsEmptyWhenKaKtFileIsNull() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        assertNotNull("Session must have at least one source file", ktFile)

        // Pass null kaKtFile — must return empty without throwing
        val result = KaSuppressionCache.getAnnotationFqNames(ktFile!!, null)
        assertTrue("Result must be empty when kaKtFile is null", result.isEmpty())
    }

    /**
     * Verifies that [KaSuppressionCache.getAnnotationFqNames] returns a non-empty list
     * for a [KtFile] itself when K2 is available. Since a [KtFile] is a [KtAnnotated],
     * this exercises the K2 analysis path without requiring a specially annotated fixture.
     *
     * The returned list may be empty if the file has no annotations — the test only
     * checks that the call succeeds without throwing.
     */
    fun testGetAnnotationFqNames_doesNotThrowForValidKaKtFile() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val kaKtFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        assertNotNull("Session must have at least one source file", kaKtFile)

        // Must complete without exception
        val result = KaSuppressionCache.getAnnotationFqNames(kaKtFile!!, kaKtFile)
        assertNotNull("Result must not be null", result)
    }
}
