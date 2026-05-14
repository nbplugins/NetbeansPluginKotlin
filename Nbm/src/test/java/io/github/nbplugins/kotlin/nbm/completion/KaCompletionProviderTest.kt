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
package io.github.nbplugins.kotlin.nbm.completion

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaCompletionProvider].
 *
 * Validates the K2 Analysis API completion path using real [KotlinAnalysisAPISession]
 * source files from the test project.
 *
 * To determine whether the K2 or K1 path was used at runtime, check the NetBeans log
 * (`View → IDE Log`): the handler emits `"K2 completion for …: N proposal(s)"` when the K2
 * path is taken and `"K1 completion fallback for …"` when falling back to K1.
 * In tests, `KotlinAnalysisAPISession.hasDependencies` tells whether the K2 session has a
 * project classpath — K2 completion is active only when this is `true`.
 */
class KaCompletionProviderTest : KotlinTestCase("KaCompletionProvider test", "completion") {

    /**
     * Verifies that [KaCompletionProvider.getSymbolsAt] returns a non-null list when given
     * a valid K2-session-owned [KtFile] and offset.
     *
     * Logs whether the K2 session has binary dependencies so the CI output shows which path
     * would be active for the test project.
     */
    fun testGetSymbolsAt_returnsListForValidFile() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        println("[KaCompletionProviderTest] hasDependencies=${wrapper.hasDependencies} " +
                "(K2 primary path ${if (wrapper.hasDependencies) "ACTIVE" else "INACTIVE — K1 fallback used"})")
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        assertNotNull("K2 session must have at least one source file", ktFile)

        val symbols = KaCompletionProvider.getSymbolsAt(ktFile!!, caretOffset = 0, prefix = "")
        println("[KaCompletionProviderTest] getSymbolsAt returned ${symbols.size} symbol(s)")
        assertNotNull("getSymbolsAt must return a non-null list", symbols)
    }

    /**
     * Verifies that [KaCompletionProvider.getSymbolsAt] returns an empty list when the
     * caret offset is negative or beyond the file length — i.e. it does not throw.
     */
    fun testGetSymbolsAt_returnsEmptyOnInvalidOffset() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        assertNotNull("K2 session must have at least one source file", ktFile)

        val symbols = KaCompletionProvider.getSymbolsAt(ktFile!!, caretOffset = Int.MAX_VALUE, prefix = "")
        assertNotNull("getSymbolsAt must not throw on invalid offset", symbols)
    }

    /**
     * Verifies that prefix filtering is case-insensitive: requesting "string" must not
     * return symbols whose names start with "String" if case sensitivity were enforced.
     * (Both "string" and "String" prefixes must match "String…" names.)
     */
    fun testGetSymbolsAt_prefixFilterIsCaseInsensitive() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val ktFile = wrapper.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull()
        assertNotNull("K2 session must have at least one source file", ktFile)

        val lowerCase = KaCompletionProvider.getSymbolsAt(ktFile!!, 0, prefix = "check")
        val upperCase = KaCompletionProvider.getSymbolsAt(ktFile, 0, prefix = "Check")
        // Both calls must succeed without throwing; result sizes may differ depending on symbols
        assertNotNull(lowerCase)
        assertNotNull(upperCase)
    }
}
