/*******************************************************************************
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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.idea.search.KotlinSearchUsagesSupport
import org.jetbrains.kotlin.idea.search.declarationsSearch.forEachOverridingElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Tests the standalone K2 hierarchy bridge used by copied IDEA refactoring code. */
class KotlinStandaloneHierarchySearchTest : KotlinTestCase("KotlinStandaloneHierarchySearchTest", "hierarchySearch") {
    /** Enumerates direct and transitive Kotlin overrides and honors processor cancellation. */
    fun testForEachOverridingElement_returnsOverrideChainAndHonorsCancellation() {
        val fixture = createFixture() ?: return
        try {
            val rootFunction = fixture.function("Root", "ping")
            val overrides = mutableListOf<String>()

            rootFunction.forEachOverridingElement { _, overriding ->
                overrides += (overriding as? KtNamedFunction)
                    ?.getParentOfType<KtClass>(strict = true)
                    ?.name
                    .orEmpty()
                true
            }

            assertEquals("Expected the complete override chain", setOf("Middle", "Leaf"), overrides.toSet())

            var invocations = 0
            rootFunction.forEachOverridingElement { _, _ ->
                invocations++
                false
            }
            assertEquals("A false processor result must stop hierarchy iteration", 1, invocations)
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Finds actual super declarations and rejects unrelated same-named declarations. */
    fun testKotlinSearchUsagesSupport_recognizesOnlyActualOverrides() {
        val fixture = createFixture() ?: return
        try {
            val rootFunction = fixture.function("Root", "ping")
            val middleFunction = fixture.function("Middle", "ping")
            val leafFunction = fixture.function("Leaf", "ping")
            val unrelatedFunction = fixture.function("Unrelated", "ping")
            val support = KotlinSearchUsagesSupport

            assertEquals(
                "Expected Middle.ping to expose Root.ping as its direct super method",
                listOf(rootFunction),
                KotlinSearchUsagesSupport.SearchUtils.findSuperMethodsNoWrapping(middleFunction),
            )
            assertTrue("Expected Leaf.ping to override Root.ping", support.isCallableOverride(rootFunction, leafFunction))
            assertTrue("Expected Root.ping to be override-related to Leaf.ping", support.isCallableOverride(leafFunction, rootFunction))
            assertFalse("Same name alone must not make a callable an override", support.isCallableOverride(rootFunction, unrelatedFunction))
        } finally {
            fixture.directory.toFile().deleteRecursively()
        }
    }

    /** Builds a multi-file standalone K2 session with an override chain and a name collision. */
    private fun createFixture(): Fixture? {
        val stdlib = findKotlinStdlib() ?: return null
        val directory = Files.createTempDirectory("nbkotlin-hierarchy-search")
        Files.writeString(directory.resolve("Root.kt"), "package hierarchy\n\nopen class Root {\n    open fun ping(): Int = 0\n}\n")
        Files.writeString(directory.resolve("Middle.kt"), "package hierarchy\n\nopen class Middle : Root() {\n    override fun ping(): Int = 1\n}\n")
        Files.writeString(directory.resolve("Leaf.kt"), "package hierarchy\n\nclass Leaf : Middle() {\n    override fun ping(): Int = 2\n}\n")
        Files.writeString(directory.resolve("Unrelated.kt"), "package hierarchy\n\nclass Unrelated {\n    fun ping(): Int = 3\n}\n")
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "hierarchy-search-integration",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(directory),
        )
        return Fixture(directory, session)
    }

    /** Locates the Kotlin standard library used by standalone Analysis API tests. */
    private fun findKotlinStdlib(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /** Owns the standalone analysis session and temporary source directory for one test. */
    private data class Fixture(
        val directory: Path,
        val session: KotlinAnalysisAPISession,
    ) {
        /** Finds the named function in the named class from the session-owned source PSI. */
        fun function(className: String, functionName: String): KtNamedFunction = session.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<org.jetbrains.kotlin.psi.KtFile>()
            .flatMap { it.declarations.filterIsInstance<KtClass>() }
            .single { it.name == className }
            .declarations
            .filterIsInstance<KtNamedFunction>()
            .single { it.name == functionName }
    }
}
