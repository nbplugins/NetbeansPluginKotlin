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
 * Guards that the committed `preview-highlights.txt` resource stays in sync with `preview.kt`.
 *
 * The companion resource is a static snapshot of the K2 semantic highlights for the preview
 * file, used because the Fonts & Colors preview pane has no live K2 session
 * (see [PreviewSemanticHighlightsLoader]). If `preview.kt` is edited without regenerating the
 * resource, the offsets drift and the preview shows wrong colors. This test recomputes the
 * highlights from the current `preview.kt` and asserts the resource matches.
 *
 * When [testResourceMatchesPreviewFile] fails, re-run [KotlinPreviewHighlightsGeneratorTest]
 * (or read the failure message, which prints the up-to-date content) and replace
 * `preview-highlights.txt`.
 *
 * Skips gracefully when `kotlin-stdlib` is not on the test classpath, matching the style of
 * [KaSemanticHighlightingVisitorTest].
 */
class PreviewSemanticHighlightsConsistencyTest : NbTestCase("PreviewSemanticHighlightsConsistency") {

    override fun setUp() {
        super.setUp()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
    }

    /** Verifies every committed range lies within the bounds of the current `preview.kt`. */
    fun testRangesWithinPreviewBounds() {
        val content = PreviewHighlightsTestSupport.loadPreviewContent()
        assertNotNull("preview.kt resource not found", content)
        val length = content!!.length
        for ((range, _) in PreviewSemanticHighlightsLoader.load()) {
            assertTrue(
                "Range ${range.start}..${range.end} exceeds preview.kt length $length " +
                    "— regenerate preview-highlights.txt",
                range.end <= length
            )
        }
    }

    /** Recomputes highlights from `preview.kt` and asserts the committed resource matches. */
    fun testResourceMatchesPreviewFile() {
        val expected = PreviewHighlightsTestSupport.computeExpectedHighlights()
        if (expected == null) {
            println("PreviewSemanticHighlightsConsistencyTest: skipping — kotlin-stdlib not on classpath")
            return
        }
        val actual = PreviewSemanticHighlightsLoader.load()

        // Compare key sets exactly and color names per range as sets (order is not significant
        // for correspondence). A mismatch means preview.kt changed without regenerating the file.
        val expectedAsSets = expected.mapValues { it.value.toSet() }
        val actualAsSets = actual.mapValues { it.value.toSet() }

        if (expectedAsSets != actualAsSets) {
            fail(
                "preview-highlights.txt is out of sync with preview.kt.\n" +
                    "Regenerate it (KotlinPreviewHighlightsGeneratorTest) with this content:\n\n" +
                    PreviewHighlightsTestSupport.formatAsResource(expected) + "\n"
            )
        }
    }
}
