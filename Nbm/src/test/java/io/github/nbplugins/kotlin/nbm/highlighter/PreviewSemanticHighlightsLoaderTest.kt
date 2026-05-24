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

import org.netbeans.junit.NbTestCase
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unit tests for [PreviewSemanticHighlightsLoader].
 *
 * Verifies that the pre-computed `preview-highlights.txt` resource can be parsed correctly
 * and that all referenced color names exist in `FontAndColors.xml`.
 */
class PreviewSemanticHighlightsLoaderTest : NbTestCase("PreviewSemanticHighlightsLoader") {

    /** Reads all `<fontcolor name="...">` attribute values from `FontAndColors.xml`. */
    private fun loadKnownColorNames(): Set<String> {
        val stream = PreviewSemanticHighlightsLoaderTest::class.java
            .getResourceAsStream("/io/github/nbplugins/kotlin/nbm/FontAndColors.xml")
            ?: return emptySet()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val nodes = doc.getElementsByTagName("fontcolor")
        return buildSet {
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                val name = element.getAttribute("name")
                if (name.isNotEmpty()) add(name)
            }
        }
    }

    /** Verifies that [PreviewSemanticHighlightsLoader.load] returns a non-empty map. */
    fun testLoadReturnNonEmpty() {
        val highlights = PreviewSemanticHighlightsLoader.load()
        assertTrue("preview-highlights.txt must contain at least one entry", highlights.isNotEmpty())
    }

    /** Verifies that all offset ranges have valid non-negative offsets with start < end. */
    fun testAllRangesAreValid() {
        val highlights = PreviewSemanticHighlightsLoader.load()
        for ((range, _) in highlights) {
            assertTrue("start offset must be non-negative: ${range.start}", range.start >= 0)
            assertTrue("start must be < end: ${range.start}..${range.end}", range.start < range.end)
        }
    }

    /** Verifies that every color name referenced in the resource exists in FontAndColors.xml. */
    fun testAllColorNamesExistInFontAndColors() {
        val highlights = PreviewSemanticHighlightsLoader.load()
        val knownColors = loadKnownColorNames()
        assertTrue("Could not load FontAndColors.xml", knownColors.isNotEmpty())

        val unknown = highlights.values
            .flatten()
            .filter { it !in knownColors }
            .distinct()
            .sorted()

        assertTrue(
            "Color names referenced in preview-highlights.txt but absent in FontAndColors.xml: $unknown",
            unknown.isEmpty()
        )
    }

    /** Verifies that [PreviewSemanticHighlightsLoader.load] returns the same instance on repeated calls (caching). */
    fun testLoadIsCached() {
        val first = PreviewSemanticHighlightsLoader.load()
        val second = PreviewSemanticHighlightsLoader.load()
        assertSame("load() must return the same cached instance", first, second)
    }
}
