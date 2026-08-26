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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.netbeans.junit.NbTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Regression tests for direct caret target selection by [KotlinInlineVariableAction]. */
class KotlinInlineVariableActionTest : NbTestCase("KotlinInlineVariableActionTest") {

    /** Finds Kotlin stdlib on the test classpath for a standalone K2 session. */
    private fun findKotlinStdlib(): Path? =
        System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull {
                it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists()
            }

    /** Runs [action] with a temporary session-backed Kotlin source file. */
    private fun withKtFile(source: String, action: (KtFile) -> Unit) {
        val stdlib = findKotlinStdlib()
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping Inline action target test")
                return
            }
        val dir = Files.createTempDirectory("nbkotlin-inline-action")
        try {
            val sourceFile = dir.resolve("file.kt")
            Files.writeString(sourceFile, source)
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "inline-action-target",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(dir),
            )
            val ktFile = session.getKtFileForPath(sourceFile.toString())
                ?: error("Failed to obtain KtFile for $sourceFile")
            action(ktFile)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** A local property declaration inside `main` must not be mistaken for its enclosing function. */
    fun testPropertyDeclarationInsideFunction_resolvesProperty() {
        val source = """
            fun main() {
                var value = 42
                println(value)
            }
        """.trimIndent()
        withKtFile(source) { ktFile ->
            val target = KotlinInlineVariableAction().resolveTargetAt(ktFile, source.indexOf("value ="))

            assertTrue("Expected a property target, got $target", target is KtProperty)
            assertEquals("value", (target as KtProperty).name)
        }
    }

    /** A property reference must resolve to its declaration rather than the enclosing function. */
    fun testPropertyUsageInsideFunction_resolvesProperty() {
        val source = """
            fun main() {
                var value = 42
                println(value)
            }
        """.trimIndent()
        withKtFile(source) { ktFile ->
            val target = KotlinInlineVariableAction().resolveTargetAt(ktFile, source.lastIndexOf("value"))

            assertTrue("Expected a property target, got $target", target is KtProperty)
            assertEquals("value", (target as KtProperty).name)
        }
    }

    /** A selected property usage resolves even when the selection end is immediately after its name. */
    fun testSelectedPropertyUsage_resolvesProperty() {
        val source = """
            fun main() {
                var value = 42
                println(value)
            }
        """.trimIndent()
        withKtFile(source) { ktFile ->
            val start = source.lastIndexOf("value")
            val target = KotlinInlineVariableAction().resolveTargetAt(ktFile, start, start + "value".length)

            assertTrue("Expected a property target, got $target", target is KtProperty)
            assertEquals("value", (target as KtProperty).name)
        }
    }

    /** A selected function call remains a valid Inline Function target. */
    fun testSelectedFunctionCall_resolvesFunction() {
        val source = """
            fun greet() = "hello"

            fun main() = println(greet())
        """.trimIndent()
        withKtFile(source) { ktFile ->
            val start = source.lastIndexOf("greet")
            val target = KotlinInlineVariableAction().resolveTargetAt(ktFile, start, start + "greet".length)

            assertTrue("Expected a function target, got $target", target is KtNamedFunction)
            assertEquals("greet", (target as KtNamedFunction).name)
        }
    }

    /** A function declaration remains a valid Inline Function target. */
    fun testFunctionDeclaration_resolvesFunction() {
        val source = """
            fun greet() = "hello"

            fun main() = println(greet())
        """.trimIndent()
        withKtFile(source) { ktFile ->
            val target = KotlinInlineVariableAction().resolveTargetAt(ktFile, source.indexOf("greet"))

            assertTrue("Expected a function target, got $target", target is KtNamedFunction)
            assertEquals("greet", (target as KtNamedFunction).name)
        }
    }

    /** A function call remains a valid Inline Function target. */
    fun testFunctionCall_resolvesFunction() {
        val source = """
            fun greet() = "hello"

            fun main() = println(greet())
        """.trimIndent()
        withKtFile(source) { ktFile ->
            val target = KotlinInlineVariableAction().resolveTargetAt(ktFile, source.lastIndexOf("greet"))

            assertTrue("Expected a function target, got $target", target is KtNamedFunction)
            assertEquals("greet", (target as KtNamedFunction).name)
        }
    }

    /** A body expression with no target declaration must not accidentally select `main`. */
    fun testUnrelatedBodyExpression_returnsNull() {
        val source = """
            fun main() {
                println(42)
            }
        """.trimIndent()
        withKtFile(source) { ktFile ->
            val target = KotlinInlineVariableAction().resolveTargetAt(ktFile, source.indexOf("42"))

            assertNull("An unrelated expression must not select the enclosing function", target)
        }
    }
}
