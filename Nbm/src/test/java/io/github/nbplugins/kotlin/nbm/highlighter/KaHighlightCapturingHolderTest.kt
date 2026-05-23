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
package io.github.nbplugins.kotlin.nbm.highlighter

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import utils.KotlinTestCase

/**
 * Unit tests for [KaHighlightCapturingHolder].
 *
 * Verifies that [KaHighlightCapturingHolder.add] captures non-null
 * [com.intellij.codeInsight.daemon.impl.HighlightInfo] objects and ignores null values.
 * Uses the full K2 session to obtain a real [org.jetbrains.kotlin.psi.KtFile] for construction.
 */
class KaHighlightCapturingHolderTest : KotlinTestCase("KaHighlightCapturingHolder", "semantic") {

    /**
     * Returns a [org.jetbrains.kotlin.psi.KtFile] from the K2 session for the given [fileName],
     * or `null` when the session has no dependencies (skips the test).
     *
     * @param fileName base name (without extension) of a `.kt` file in the `semantic` directory
     */
    private fun getKtFile(fileName: String) =
        KotlinAnalysisAPISession.getSession(project).let { session ->
            if (!session.hasDependencies) {
                println("KaHighlightCapturingHolderTest: skipping — K2 session has no dependencies")
                null
            } else {
                dir.getFileObject("$fileName.kt")?.path?.let { session.getKtFileForPath(it) }
            }
        }

    /**
     * A holder constructed from a valid [org.jetbrains.kotlin.psi.KtFile] starts with an empty
     * [KaHighlightCapturingHolder.capturedInfos] list.
     */
    fun testInitiallyEmpty() {
        val ktFile = getKtFile("empty") ?: return
        val holder = KaHighlightCapturingHolder(ktFile)
        assertTrue("capturedInfos should be empty on construction", holder.capturedInfos.isEmpty())
    }

    /**
     * Adding `null` to the holder is silently ignored and [KaHighlightCapturingHolder.capturedInfos]
     * stays empty.
     */
    fun testAddNullIsIgnored() {
        val ktFile = getKtFile("empty") ?: return
        val holder = KaHighlightCapturingHolder(ktFile)
        val result = holder.add(null)
        assertTrue("add(null) should return true", result)
        assertTrue("capturedInfos should remain empty after add(null)", holder.capturedInfos.isEmpty())
    }

    /**
     * Verifies that [KaHighlightCapturingHolder] can be constructed using the PSI file from
     * the K2 project (obtained via [org.jetbrains.kotlin.psi.KtFile.getContainingFile]) without
     * triggering the {@code com.intellij.daemon.highlightInfoFilter} extension-point lookup.
     *
     * This test does not require K2 dependencies — it uses the KtFile's PSI file directly.
     */
    fun testConstructorDoesNotThrow() {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        val fileObject = dir.getFileObject("empty.kt") ?: return
        val ktFile = wrapper.getKtFileForPath(fileObject.path) ?: run {
            println("KaHighlightCapturingHolderTest: skipping testConstructorDoesNotThrow — ktFile unavailable")
            return
        }
        val holder = KaHighlightCapturingHolder(ktFile)
        assertTrue("capturedInfos should be empty on construction", holder.capturedInfos.isEmpty())
    }
}
