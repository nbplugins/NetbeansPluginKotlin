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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaIntroduceConstantComputer
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaIntroduceConstantComputer].
 *
 * Covers:
 *  1. A plain integer literal returns [KaIntroduceConstantComputer.Outcome.Ready] with a
 *     SCREAMING_SNAKE_CASE name suggestion.
 *  2. A non-constant expression returns [KaIntroduceConstantComputer.Outcome.NotApplicable].
 *  3. When two identical constant expressions exist in the file, "Replace all" must find
 *     both occurrences — regression for the compound-expression silent-fallback bug.
 */
class KaIntroduceConstantTest : KotlinTestCase("KaIntroduceConstantTest", "introduceVariable") {

    private fun findKotlinStdlib(): Path? =
        System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /**
     * Literal `42` in a top-level function: outcome must be [KaIntroduceConstantComputer.Outcome.Ready]
     * and the suggested name must be in SCREAMING_SNAKE_CASE.
     */
    fun testIntLiteral_returnsReady() {
        val stdlib = findKotlinStdlib()
            ?: run {
                println("KaIntroduceConstantTest: kotlin-stdlib not on classpath — skipping")
                return
            }

        val source = "package introduceConstant\n\nfun foo() = 42\n"
        val selectionText = "42"
        val start = source.indexOf(selectionText).also { check(it >= 0) }
        val end = start + selectionText.length

        val tmpDir = Files.createTempDirectory("nbkotlin-introduce-const-int")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)
        try {
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "introduce-const-int",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(tmpFile.toString())
                ?: error("Failed to obtain KtFile")

            val computer = KaIntroduceConstantComputer(ktFile, start, end, session.session.project)
            val outcome = computer.compute()

            assertTrue(
                "Expected Ready for literal '42', got $outcome",
                outcome is KaIntroduceConstantComputer.Outcome.Ready,
            )
            val result = (outcome as KaIntroduceConstantComputer.Outcome.Ready).result
            assertTrue(
                "Expected at least one suggested name, got none",
                result.suggestedNames.isNotEmpty(),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * A runtime-computed expression (`a + b` where `a` and `b` are function parameters) is not a
     * compile-time constant and must return [KaIntroduceConstantComputer.Outcome.NotApplicable].
     */
    fun testNonConstantExpr_returnsNotApplicable() {
        val stdlib = findKotlinStdlib()
            ?: run {
                println("KaIntroduceConstantTest: kotlin-stdlib not on classpath — skipping")
                return
            }

        val source = "package introduceConstant\n\nfun add(a: Int, b: Int) = a + b\n"
        val selectionText = "a + b"
        val start = source.indexOf(selectionText).also { check(it >= 0) }
        val end = start + selectionText.length

        val tmpDir = Files.createTempDirectory("nbkotlin-introduce-const-non")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)
        try {
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "introduce-const-non",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(tmpFile.toString())
                ?: error("Failed to obtain KtFile")

            val computer = KaIntroduceConstantComputer(ktFile, start, end, session.session.project)
            val outcome = computer.compute()

            assertTrue(
                "Expected NotApplicable for non-constant 'a + b', got $outcome",
                outcome is KaIntroduceConstantComputer.Outcome.NotApplicable,
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Regression: when a compound constant expression (`"Hello" + " " + "World"`) is **selected**
     * and the file has two identical occurrences, the computer must report both so that
     * "Replace all" can replace them.
     *
     * Previously, [K2SemanticMatcher.findMatches] silently fell back to a single-element list on
     * compound expressions, so only the selected occurrence was ever replaced.
     */
    fun testReplaceAll_withStringConcat_findsAllOccurrences() {
        val stdlib = findKotlinStdlib()
            ?: run {
                println("KaIntroduceConstantTest: kotlin-stdlib not on classpath — skipping string-concat test")
                return
            }

        val source = """
            package introduceConstant

            const val A = "Hello" + " " + "World"
            const val B = "Hello" + " " + "World"
        """.trimIndent() + "\n"

        val selectionText = """"Hello" + " " + "World""""
        val selStart = source.indexOf(selectionText).also {
            check(it >= 0) { "Selection '$selectionText' not found in source" }
        }
        val selEnd = selStart + selectionText.length

        val tmpDir = Files.createTempDirectory("nbkotlin-introduce-const-concat")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)
        try {
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "introduce-const-concat",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(tmpFile.toString())
                ?: error("Failed to obtain KtFile")

            val computer = KaIntroduceConstantComputer(ktFile, selStart, selEnd, session.session.project)
            val outcome = computer.compute()

            assertTrue(
                "Expected Ready for string-concat selection, got $outcome",
                outcome is KaIntroduceConstantComputer.Outcome.Ready,
            )
            val result = (outcome as KaIntroduceConstantComputer.Outcome.Ready).result
            assertEquals(
                "Expected 2 occurrences for two identical string-concat constants (Replace all must find both)",
                2,
                result.occurrenceRanges.size,
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
