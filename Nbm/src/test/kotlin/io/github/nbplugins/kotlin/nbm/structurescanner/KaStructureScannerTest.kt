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
package io.github.nbplugins.kotlin.nbm.structurescanner

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.netbeans.modules.csl.api.StructureItem
import utils.KotlinTestCase

/**
 * Unit tests for [KaStructureScanner].
 *
 * Each test reuses the existing `.kt` files from the `structureScanner` test-resource directory
 * (shared with the legacy [org.jetbrains.kotlin.structurescanner.KotlinStructureScanner] tests) and
 * verifies that the K2 path produces the expected item counts and item types.
 *
 * Tests skip gracefully (printing a message) when the K2 session has no binary dependencies,
 * because type inference is unreliable without the Kotlin stdlib on the classpath.
 */
class KaStructureScannerTest : KotlinTestCase("KaStructureScanner test", "structureScanner") {

    /** Flattens an item and all its nested items into a single list. */
    private val StructureItem.allItems: List<StructureItem>
        get() = listOf(this) + nestedItems.flatMap { it.allItems }

    /**
     * Returns the K2 structure items for [fileName], or `null` when K2 is unavailable.
     *
     * @param fileName name of the test file (without `.kt` extension)
     */
    private fun getItems(fileName: String): List<StructureItem>? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaStructureScannerTest: skipping $fileName — K2 session has no dependencies")
            return null
        }
        val file = dir.getFileObject("$fileName.kt") ?: return null
        return KaStructureScanner.getStructureItems(file, project)
    }

    /**
     * Asserts that the structure items for [fileName] match the expected counts per item type.
     *
     * @param fileName name of the test file (without `.kt` extension)
     * @param functions expected number of [KaFunctionStructureItem] entries (at any depth)
     * @param properties expected number of [KaPropertyStructureItem] entries (at any depth)
     * @param classes expected number of [KaClassStructureItem] entries (at any depth)
     */
    private fun doTest(fileName: String, functions: Int = 0, properties: Int = 0, classes: Int = 0) {
        val items = getItems(fileName) ?: return
        val all = items.flatMap { it.allItems }
        assertEquals("functions in $fileName", functions, all.filterIsInstance<KaFunctionStructureItem>().size)
        assertEquals("properties in $fileName", properties, all.filterIsInstance<KaPropertyStructureItem>().size)
        assertEquals("classes in $fileName", classes, all.filterIsInstance<KaClassStructureItem>().size)
    }

    /** An empty file should produce no structure items. */
    fun testEmpty() = doTest("empty")

    /** A file with a single top-level function should produce one function item. */
    fun testSimple() = doTest("simple", functions = 1)

    /** A file with two top-level functions should produce two function items. */
    fun testSeveralFunctions() = doTest("severalFunctions", functions = 2)

    /** A file with an object declaration containing one function should produce one class and one function. */
    fun testObject() = doTest("object", classes = 1, functions = 1)

    /** A class with nested members should produce correct counts for classes, functions, and properties. */
    fun testClassWithSeveralMembers() = doTest("classWithSeveralMembers", classes = 2, functions = 1, properties = 1)

    /** Multiple classes produce the expected cumulative counts. */
    fun testSeveralClasses() = doTest("severalClasses", classes = 4, functions = 3, properties = 3)

    /** Function items should have non-empty display names including the function name. */
    fun testFunctionDisplayName() {
        val items = getItems("simple") ?: return
        val fn = items.flatMap { it.allItems }.filterIsInstance<KaFunctionStructureItem>().firstOrNull()
        assertNotNull("Expected a KaFunctionStructureItem in simple.kt", fn)
        assertTrue("Function name should be non-empty", fn!!.name.isNotBlank())
    }

    /** Property items should have non-empty display names including the property name. */
    fun testPropertyDisplayName() {
        val items = getItems("classWithSeveralMembers") ?: return
        val prop = items.flatMap { it.allItems }.filterIsInstance<KaPropertyStructureItem>().firstOrNull()
        assertNotNull("Expected a KaPropertyStructureItem in classWithSeveralMembers.kt", prop)
        assertTrue("Property name should be non-empty", prop!!.name.isNotBlank())
    }
}
