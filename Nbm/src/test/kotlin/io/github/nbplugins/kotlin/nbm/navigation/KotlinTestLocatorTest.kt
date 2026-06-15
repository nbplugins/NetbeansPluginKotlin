/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
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
package io.github.nbplugins.kotlin.nbm.navigation

import org.netbeans.spi.gototest.TestLocator
import utils.KotlinTestCase

/**
 * Unit tests for [KotlinTestLocator].
 *
 * Uses the `navigation/` test fixture directory which contains:
 * - `Foo.kt` — a source file (TESTED classification)
 * - `FooTest.kt` — a test file (TEST classification)
 *
 * [findOpposite] integration is verified by checking that `Foo.kt` → `FooTest.kt`
 * pairing works when both files are present in the test project.
 */
class KotlinTestLocatorTest : KotlinTestCase("KotlinTestLocator", "navigation") {

    private val locator = KotlinTestLocator()

    /**
     * Verifies [KotlinTestLocator.appliesTo] returns `true` for `.kt` files.
     */
    fun testAppliesToKotlinFile() {
        val fo = dir.getFileObject("Foo.kt") ?: return
        assertTrue("appliesTo must return true for .kt files", locator.appliesTo(fo))
    }

    /**
     * Verifies [KotlinTestLocator.appliesTo] returns `false` for non-Kotlin files.
     */
    fun testAppliesToReturnsFalseForJavaFile() {
        val fo = dir.getFileObject("JavaClass.java") ?: run {
            println("KotlinTestLocatorTest.testAppliesToReturnsFalseForJavaFile: JavaClass.java not found, skipping")
            return
        }
        assertFalse("appliesTo must return false for .java files", locator.appliesTo(fo))
    }

    /**
     * Verifies that `FooTest.kt` is classified as [TestLocator.FileType.TEST].
     */
    fun testGetFileTypeForTestFile() {
        val fo = dir.getFileObject("FooTest.kt") ?: return
        assertEquals("FooTest.kt must be classified as TEST",
            TestLocator.FileType.TEST, locator.getFileType(fo))
    }

    /**
     * Verifies that `Foo.kt` is classified as [TestLocator.FileType.TESTED].
     */
    fun testGetFileTypeForSourceFile() {
        val fo = dir.getFileObject("Foo.kt") ?: return
        assertEquals("Foo.kt must be classified as TESTED",
            TestLocator.FileType.TESTED, locator.getFileType(fo))
    }

    /**
     * Verifies that [KotlinTestLocator.findOpposite] for `Foo.kt` returns a result pointing to
     * `FooTest.kt` when both files exist in the project.
     */
    fun testFindOppositeFromSourceToTest() {
        val fo = dir.getFileObject("Foo.kt") ?: return
        val result = locator.findOpposite(fo, -1)
        assertNull("findOpposite must not return an error when FooTest.kt exists",
            result.errorMessage)
        assertNotNull("findOpposite must return a FileObject pointing to FooTest.kt",
            result.fileObject)
        assertEquals("Opposite of Foo.kt must be FooTest.kt",
            "FooTest.kt", result.fileObject?.nameExt)
    }

    /**
     * Verifies that [KotlinTestLocator.findOpposite] for `FooTest.kt` returns a result pointing
     * to `Foo.kt`.
     */
    fun testFindOppositeFromTestToSource() {
        val fo = dir.getFileObject("FooTest.kt") ?: return
        val result = locator.findOpposite(fo, -1)
        assertNull("findOpposite must not return an error when Foo.kt exists",
            result.errorMessage)
        assertNotNull("findOpposite must return a FileObject pointing to Foo.kt",
            result.fileObject)
        assertEquals("Opposite of FooTest.kt must be Foo.kt",
            "Foo.kt", result.fileObject?.nameExt)
    }
}
