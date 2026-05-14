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
import org.jetbrains.kotlin.highlighter.semanticanalyzer.KotlinHighlightingAttributes
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.modules.csl.api.ColoringAttributes
import org.netbeans.modules.csl.api.OffsetRange
import utils.KotlinTestCase
import utils.carets
import utils.getDocumentForFileObject

typealias KaAttr = KotlinHighlightingAttributes

/**
 * Unit tests for [KaSemanticHighlightingVisitor].
 *
 * Each test opens one of the existing `.kt` files from the `semantic` test-resource directory,
 * obtains the corresponding K2 [KtFile] from [KotlinAnalysisAPISession], runs the visitor, and
 * verifies that the expected [KotlinHighlightingAttributes] are produced at the caret positions
 * marked in the companion `.caret` file.
 *
 * Tests skip gracefully (with a log message) when the K2 session has no binary dependencies,
 * because symbol resolution is unreliable without the Kotlin stdlib on the classpath.
 */
class KaSemanticHighlightingVisitorTest : KotlinTestCase("KaSemanticHighlightingVisitor", "semantic") {

    /** Converts a flat list of offsets to consecutive [OffsetRange] pairs. */
    private fun List<Int>.toOffsetRanges(): List<OffsetRange> {
        val ranges = mutableListOf<OffsetRange>()
        var i = 0
        while (i < size - 1) {
            ranges.add(OffsetRange(this[i], this[i + 1]))
            i += 2
        }
        return ranges
    }

    /**
     * Builds the expected highlight map from the caret file and the given attribute list.
     *
     * @param fileName name of the test file (without extension)
     * @param attrs    expected attributes in declaration order, matching caret positions
     * @return map from caret-derived [OffsetRange] to the expected [ColoringAttributes] set
     */
    private fun attrs(
        fileName: String,
        attrs: List<KaAttr>,
    ): Map<OffsetRange, Set<ColoringAttributes>> {
        val doc = getDocumentForFileObject(dir, "$fileName.caret")
        val ranges = doc.carets().toOffsetRanges()
        return buildMap {
            attrs.forEachIndexed { i, attr -> put(ranges[i], attr.styleKey) }
        }
    }

    /**
     * Obtains the K2 [KtFile] for [fileName] from [KotlinAnalysisAPISession].
     *
     * Returns `null` (and logs a skip message) when the session has no binary dependencies or
     * when the file is not registered in the session.
     *
     * @param fileName name of the test file (without extension)
     * @return the K2-owned [KtFile], or `null` if K2 analysis is unavailable
     */
    private fun getKaKtFile(fileName: String): KtFile? {
        val wrapper = KotlinAnalysisAPISession.getSession(project)
        if (!wrapper.hasDependencies) {
            println("KaSemanticHighlightingVisitorTest: skipping $fileName — K2 session has no dependencies")
            return null
        }
        val fileObject = dir.getFileObject("$fileName.kt") ?: return null
        return wrapper.getKtFileForPath(fileObject.path)
    }

    /**
     * Runs [KaSemanticHighlightingVisitor] for [fileName] and asserts that the returned
     * highlights contain all entries in the expected map.
     *
     * @param fileName name of the test file (without extension)
     * @param attrs    expected [KotlinHighlightingAttributes] in caret order
     */
    private fun doTest(fileName: String, vararg attrs: KaAttr) {
        val kaKtFile = getKaKtFile(fileName) ?: return
        val highlights = KaSemanticHighlightingVisitor(kaKtFile).computeHighlightingRanges()
        val expected = attrs(fileName, attrs.toList())
        assertTrue(
            "Missing highlights for $fileName. Expected: $expected, got: $highlights",
            highlights.entries.containsAll(expected.entries),
        )
    }

    /** Empty file should produce no highlights. */
    fun testEmpty() {
        val kaKtFile = getKaKtFile("empty") ?: return
        val highlights = KaSemanticHighlightingVisitor(kaKtFile).computeHighlightingRanges()
        assertTrue("Empty file should produce no highlights", highlights.isEmpty())
    }

    /** A single class declaration should produce one CLASS highlight. */
    fun testSimpleClass() = doTest("simpleClass", KaAttr.CLASS)

    /** A class with a property should produce CLASS and FINAL_FIELD highlights. */
    fun testClass() = doTest("class", KaAttr.CLASS, KaAttr.FINAL_FIELD)

    /** A function with local variables should produce FUNCTION_DECLARATION, LOCAL_FINAL_VARIABLE, LOCAL_VARIABLE. */
    fun testFunctionWithLocalVariables() = doTest(
        "functionWithLocalVariables",
        KaAttr.FUNCTION_DECLARATION,
        KaAttr.LOCAL_FINAL_VARIABLE,
        KaAttr.LOCAL_VARIABLE,
    )

    /** An annotation usage should be highlighted as ANNOTATION. */
    fun testAnnotation() = doTest("annotation", KaAttr.ANNOTATION)

    /** A deprecated symbol usage should be highlighted as DEPRECATED. */
    fun testDeprecated() = doTest("deprecated", KaAttr.DEPRECATED)

    /**
     * A smart-cast expression is highlighted as LOCAL_FINAL_VARIABLE.
     *
     * Smart-cast type rendering is not yet implemented in the K2 path; the test
     * only verifies the base variable attribute, not the SMART_CAST overlay.
     */
    fun testSmartCast() = doTest("smartCast", KaAttr.LOCAL_FINAL_VARIABLE)
}
