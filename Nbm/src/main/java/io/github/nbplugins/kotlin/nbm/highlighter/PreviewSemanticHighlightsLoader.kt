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

import org.jetbrains.kotlin.log.KotlinLogger
import org.netbeans.modules.csl.api.OffsetRange

/**
 * Loads pre-computed semantic highlight ranges for the Fonts & Colors settings preview.
 *
 * The preview file (`preview.kt`) is not associated with any NetBeans project, so the normal
 * K2 analysis path (`KotlinSemanticAnalyzer`) cannot run. This object provides a static
 * snapshot of the highlight ranges that the K2 visitor would produce, computed once with
 * a full standalone session (including `kotlin-stdlib`) and stored in
 * `preview-highlights.txt` alongside `preview.kt`.
 *
 * **Format of `preview-highlights.txt`**: one entry per line:
 * ```
 * <startOffset> <endOffset> <COLOR_NAME1> [COLOR_NAME2 ...]
 * ```
 * Lines that are blank or start with `#` are ignored.
 *
 * The loaded map is cached after the first call; subsequent calls return the same instance.
 *
 * To regenerate after editing `preview.kt`, run
 * `KotlinPreviewHighlightsGeneratorTest.testGenerateHighlights` and copy its stdout into
 * `preview-highlights.txt`.
 */
object PreviewSemanticHighlightsLoader {

    private const val RESOURCE = "/io/github/nbplugins/kotlin/nbm/preview-highlights.txt"

    @Volatile
    private var cached: Map<OffsetRange, List<String>>? = null

    /**
     * Returns the pre-computed highlight map, loading it from the resource on the first call.
     *
     * @return map from source [OffsetRange] to ordered list of color category names
     *         (keys in `FontAndColors.xml`); empty map if the resource cannot be read
     */
    fun load(): Map<OffsetRange, List<String>> {
        cached?.let {
            KotlinLogger.INSTANCE.logInfo("PreviewSemanticHighlightsLoader.load: returning cached ${it.size} ranges")
            return it
        }
        val result = parse()
        cached = result
        KotlinLogger.INSTANCE.logInfo("PreviewSemanticHighlightsLoader.load: parsed ${result.size} ranges")
        return result
    }

    private fun parse(): Map<OffsetRange, List<String>> {
        val stream = PreviewSemanticHighlightsLoader::class.java.getResourceAsStream(RESOURCE)
            ?: return emptyMap()
        return stream.bufferedReader().useLines { lines ->
            val map = mutableMapOf<OffsetRange, List<String>>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                val parts = trimmed.split(' ')
                if (parts.size < 3) continue
                val start = parts[0].toIntOrNull() ?: continue
                val end = parts[1].toIntOrNull() ?: continue
                if (start < 0 || end <= start) continue
                val colors = parts.drop(2).filter { it.isNotEmpty() }
                if (colors.isEmpty()) continue
                map[OffsetRange(start, end)] = colors
            }
            map
        }
    }
}
