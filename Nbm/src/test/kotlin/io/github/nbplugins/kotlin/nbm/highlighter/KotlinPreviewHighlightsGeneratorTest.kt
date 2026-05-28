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
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.netbeans.junit.NbTestCase

/**
 * Generator for `preview-highlights.txt`.
 *
 * Recomputes the semantic highlights for `preview.kt` (via [PreviewHighlightsTestSupport],
 * with `kotlin-stdlib` on the classpath) and prints them to stdout in the format expected by
 * [PreviewSemanticHighlightsLoader]:
 * ```
 * <start> <end> <COLOR1> [COLOR2 ...]
 * ```
 *
 * After running, copy the output between the `===BEGIN===` / `===END===` markers into
 * `Nbm/src/main/resources/io/github/nbplugins/kotlin/nbm/preview-highlights.txt`.
 *
 * This test is a one-shot code-generation utility — re-run it whenever `preview.kt` changes
 * and [PreviewSemanticHighlightsConsistencyTest] starts failing.
 */
class KotlinPreviewHighlightsGeneratorTest : NbTestCase("KotlinPreviewHighlightsGenerator") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    /** Prints freshly computed preview highlights to stdout for copying into the resource file. */
    fun testGenerateHighlights() {
        val highlights = PreviewHighlightsTestSupport.computeExpectedHighlights()
        if (highlights == null) {
            println("KotlinPreviewHighlightsGeneratorTest: skipping — kotlin-stdlib not on classpath")
            return
        }
        println("===BEGIN preview-highlights.txt===")
        println(PreviewHighlightsTestSupport.formatAsResource(highlights))
        println("===END preview-highlights.txt===")
        assertTrue("Expected at least one highlight range", highlights.isNotEmpty())
    }
}
