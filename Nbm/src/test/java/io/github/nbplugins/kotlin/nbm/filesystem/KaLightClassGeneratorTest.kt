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
package io.github.nbplugins.kotlin.nbm.filesystem

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import utils.KotlinTestCase

/**
 * Unit tests for [KaLightClassGenerator].
 *
 * Tests use the `structureScanner` test-resource directory (which contains Kotlin files with
 * class and function declarations) to verify that K2-generated Java stubs are syntactically
 * reasonable: non-null result, correct package declaration, class keyword present, and class
 * name matching the Kotlin declaration.
 *
 * Tests skip gracefully when the K2 session has no binary dependencies.
 */
class KaLightClassGeneratorTest : KotlinTestCase("KaLightClassGenerator test", "structureScanner") {

    /**
     * Returns the K2 Java stubs for [fileName], or `null` when K2 is unavailable.
     *
     * @param fileName name of the test file (without `.kt` extension)
     */
    private fun getStubs(fileName: String): List<Pair<String, String>>? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaLightClassGeneratorTest: skipping $fileName — K2 session has no dependencies")
            return null
        }
        val file = dir.getFileObject("$fileName.kt") ?: return null
        return KaLightClassGenerator.getJavaStubs(file, project)
    }

    /**
     * A file with a class declaration should produce at least one stub containing the class keyword
     * and the expected class name.
     */
    fun testClassStubIsGenerated() {
        val stubs = getStubs("classWithSeveralMembers") ?: return
        assertFalse("Expected at least one stub for classWithSeveralMembers.kt", stubs.isEmpty())
        val (className, code) = stubs.first()
        assertFalse("className should not be blank", className.isBlank())
        assertTrue("Stub should contain 'class' keyword", code.contains("class"))
        assertTrue("Stub should contain the class name", code.contains("ClassWithSeveralMembers"))
    }

    /**
     * A file with a class should produce a stub with a `package` declaration matching
     * the Kotlin package.
     */
    fun testPackageDeclarationIsPresent() {
        val stubs = getStubs("classWithSeveralMembers") ?: return
        if (stubs.isEmpty()) return
        val (_, code) = stubs.first()
        assertTrue("Stub should contain a package declaration", code.contains("package structureScanner"))
    }

    /**
     * An empty file should produce an empty stub list (no classes, no top-level declarations).
     */
    fun testEmptyFileProducesNoStubs() {
        val stubs = getStubs("empty") ?: return
        assertTrue("Empty file should produce no stubs", stubs.isEmpty())
    }

    /**
     * A file with only top-level functions should produce a single facade class stub.
     */
    fun testTopLevelFunctionProducesFacade() {
        val stubs = getStubs("simple") ?: return
        // simple.kt has a top-level function, so a facade class should be generated
        assertTrue("Expected a facade stub for simple.kt with top-level function", stubs.isNotEmpty())
        val (_, code) = stubs.first()
        assertTrue("Facade stub should contain 'class'", code.contains("class"))
    }
}
