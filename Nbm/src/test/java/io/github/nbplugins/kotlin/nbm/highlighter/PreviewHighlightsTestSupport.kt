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
import org.netbeans.modules.csl.api.OffsetRange
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared support for tests that work with the Fonts & Colors preview highlights.
 *
 * Recomputes the semantic highlights for `preview.kt` using a standalone K2 session with
 * `kotlin-stdlib` on the classpath — the same path the production visitor takes in a real
 * editor. Used both to generate `preview-highlights.txt`
 * ([KotlinPreviewHighlightsGeneratorTest]) and to validate that the committed resource still
 * matches the preview file ([PreviewSemanticHighlightsConsistencyTest]).
 *
 * Callers must have initialised the K2 application environment
 * (`FakeIntellijHome.startUp()` + `KotlinAnalysisAPISession.initApplicationEnvironment()`)
 * before invoking [computeExpectedHighlights]; the `NbTestCase` subclasses do this in `setUp`.
 */
object PreviewHighlightsTestSupport {

    private const val PREVIEW_RESOURCE = "/io/github/nbplugins/kotlin/nbm/preview.kt"

    /** Reads the `preview.kt` resource content, or `null` if the resource is missing. */
    fun loadPreviewContent(): String? =
        PreviewHighlightsTestSupport::class.java
            .getResourceAsStream(PREVIEW_RESOURCE)
            ?.readBytes()
            ?.decodeToString()

    /** Finds `kotlin-stdlib-*.jar` on the test classpath, or `null` if absent. */
    fun findKotlinStdlib(): Path? =
        System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /**
     * Computes the semantic highlights for `preview.kt` via a fresh standalone K2 session.
     *
     * @return the highlight map, or `null` when `kotlin-stdlib` or the preview resource is
     *         unavailable (so callers can skip gracefully, matching the project test style)
     */
    fun computeExpectedHighlights(): Map<OffsetRange, List<String>>? {
        val stdlibPath = findKotlinStdlib() ?: return null
        val previewContent = loadPreviewContent() ?: return null

        val tmpDir = Files.createTempDirectory("nbkotlin-preview")
        try {
            val tmpFile = tmpDir.resolve("preview.kt")
            Files.writeString(tmpFile, previewContent)

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "preview-highlights",
                binaryJars = listOf(stdlibPath),
                sourceRoots = listOf(tmpDir)
            )
            val kaKtFile = session.getKtFileForPath(tmpFile.toString()) ?: return null
            return KaSemanticHighlightingVisitor(kaKtFile).computeHighlightingRanges()
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /** Renders a highlight map in `preview-highlights.txt` line format, sorted by start offset. */
    fun formatAsResource(highlights: Map<OffsetRange, List<String>>): String =
        highlights.entries
            .sortedBy { it.key.start }
            .joinToString("\n") { (range, names) -> "${range.start} ${range.end} ${names.joinToString(" ")}" }
}
