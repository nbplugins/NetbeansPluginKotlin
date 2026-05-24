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
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.modules.csl.api.OffsetRange
import utils.KotlinTestCase
import utils.carets
import utils.getDocumentForFileObject

/**
 * Unit tests for [KaSemanticHighlightingVisitor].
 *
 * Each test opens one of the existing `.kt` files from the `semantic` test-resource directory,
 * obtains the corresponding K2 [KtFile] from [KotlinAnalysisAPISession], runs the visitor, and
 * verifies that the expected color category names are produced at the caret positions marked in
 * the companion `.caret` file.
 *
 * Assertions use **subset containment**: the actual color name list for a given range must contain
 * *at least* the expected names. This tolerates additional names produced by orthogonal highlight
 * passes (e.g. a function-call range that is also deprecated receives both the function-call name
 * and `"KOTLIN_DEPRECATED"`).
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
     * Builds the expected highlight map from the caret file and the given color name list.
     *
     * @param fileName name of the test file (without extension)
     * @param names    expected color category names in declaration order, matching caret positions
     * @return map from caret-derived [OffsetRange] to the expected color category name set
     */
    private fun expectedNames(
        fileName: String,
        names: List<String>,
    ): Map<OffsetRange, Set<String>> {
        val doc = getDocumentForFileObject(dir, "$fileName.caret")
        val ranges = doc.carets().toOffsetRanges()
        return buildMap {
            names.forEachIndexed { i, name -> put(ranges[i], setOf(name)) }
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
     * highlights contain at least the expected color category names at the expected ranges.
     *
     * The assertion is **subset-based**: `highlights[range]` must contain all expected names,
     * but may also contain additional names from other passes.
     *
     * @param fileName name of the test file (without extension)
     * @param names    expected color category names (FontAndColors.xml keys) in caret order
     */
    private fun doTest(fileName: String, vararg names: String) {
        val kaKtFile = getKaKtFile(fileName) ?: return
        val highlights = KaSemanticHighlightingVisitor(kaKtFile).computeHighlightingRanges()
        val expected = expectedNames(fileName, names.toList())
        val missing = expected.entries.filter { (range, expectedNames) ->
            highlights[range]?.containsAll(expectedNames) != true
        }
        assertTrue(
            "Missing highlights for $fileName.\nExpected (subset): $expected\nGot: $highlights\nMissing: $missing",
            missing.isEmpty(),
        )
    }

    /** Empty file should produce no highlights. */
    fun testEmpty() {
        val kaKtFile = getKaKtFile("empty") ?: return
        val highlights = KaSemanticHighlightingVisitor(kaKtFile).computeHighlightingRanges()
        assertTrue("Empty file should produce no highlights", highlights.isEmpty())
    }

    /** A single class declaration should produce at least a CLASS highlight at the class name. */
    fun testSimpleClass() = doTest("simpleClass", "KOTLIN_CLASS")

    /** A class with a val property should produce CLASS and INSTANCE_PROPERTY highlights. */
    fun testClass() = doTest("class", "KOTLIN_CLASS", "KOTLIN_INSTANCE_PROPERTY")

    /** A function with local variables should produce FUNCTION_DECLARATION and LOCAL_VARIABLE highlights. */
    fun testFunctionWithLocalVariables() = doTest(
        "functionWithLocalVariables",
        "KOTLIN_FUNCTION_DECLARATION",
        "KOTLIN_LOCAL_VARIABLE",
        "KOTLIN_LOCAL_VARIABLE",
    )

    /** An annotation usage should contain at least the ANNOTATION color. */
    fun testAnnotation() = doTest("annotation", "KOTLIN_ANNOTATION")

    /**
     * A deprecated symbol usage should contain at least the DEPRECATED color.
     *
     * The range may also carry function-call colors since the deprecated call site is highlighted
     * by both [KaHighlightColorMapper] and the deprecation diagnostic.
     */
    fun testDeprecated() = doTest("deprecated", "KOTLIN_DEPRECATED")

    /**
     * A smart-cast expression should contain at least LOCAL_VARIABLE color.
     */
    fun testSmartCast() = doTest("smartCast", "KOTLIN_LOCAL_VARIABLE")

    /**
     * A top-level function call site is highlighted with KOTLIN_PACKAGE_FUNCTION_CALL.
     * An extension function call site is highlighted with KOTLIN_EXTENSION_FUNCTION_CALL.
     */
    fun testFunctionCallHighlighting() {
        val kaKtFile = getKaKtFile("functionCallHighlighting") ?: return
        val highlights = KaSemanticHighlightingVisitor(kaKtFile).computeHighlightingRanges()
        val doc = getDocumentForFileObject(dir, "functionCallHighlighting.caret")
        val ranges = doc.carets().toOffsetRanges()
        assertTrue("Expected at least 2 call-site caret ranges", ranges.size >= 2)
        assertTrue(
            "topLevelFun() call site should contain KOTLIN_PACKAGE_FUNCTION_CALL",
            highlights[ranges[0]]?.contains("KOTLIN_PACKAGE_FUNCTION_CALL") == true,
        )
        assertTrue(
            "extensionFun() call site should contain KOTLIN_EXTENSION_FUNCTION_CALL",
            highlights[ranges[1]]?.contains("KOTLIN_EXTENSION_FUNCTION_CALL") == true,
        )
    }
}
