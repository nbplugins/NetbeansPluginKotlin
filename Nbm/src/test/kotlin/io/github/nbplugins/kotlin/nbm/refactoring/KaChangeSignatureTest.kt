/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureComputer
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureParameter
import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureRequest
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaChangeSignatureComputer] (E9.8, M1 scope: plain function calls and
 * parameter-name references — see [KaChangeSignatureComputer]'s class doc).
 *
 * The basic `compute()` tests use the `projForTest/src/changeSignature/` fixture-directory
 * convention (same as [KaMoveDeclarationTest]'s `topLevel`/`notApplicable`); the `apply()`
 * integration tests use real, self-contained K2 sessions over temp directories, since Change
 * Signature always needs at least one call-site file besides the declaration file.
 */
class KaChangeSignatureTest : KotlinTestCase("KaChangeSignatureTest", "changeSignature") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaChangeSignatureTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    private fun readCaretOffset(subDir: String): Int? {
        val text = dir.getFileObject(subDir)?.getFileObject("file.caret")?.asText() ?: return null
        val idx = text.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): KaChangeSignatureComputer? {
        val fileFo = dir.getFileObject(subDir)?.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaChangeSignatureComputer(ktFile, offset)
    }

    private fun findStdlibJar(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map { Path.of(it) }
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /**
     * Caret directly on the function declaration: [KaChangeSignatureComputer.compute] must return
     * [KaChangeSignatureComputer.Outcome.Ready] with a [io.github.nbplugins.kotlin.refactoring.KaChangeSignatureResult]
     * reflecting the current (unchanged) signature.
     */
    fun testCompute_onFunctionDeclaration_returnsReadyWithCurrentSignature() {
        val session = getSessionOrSkip() ?: return
        val computer = prepareComputer("topLevel", session) ?: return

        val outcome = computer.compute()
        assertTrue(
            "Expected Ready for caret on function declaration, got $outcome",
            outcome is KaChangeSignatureComputer.Outcome.Ready,
        )
        val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
        assertEquals("greet", result.declarationName)
        assertEquals(2, result.parameters.size)
        assertEquals("first", result.parameters[0].name)
        assertEquals("second", result.parameters[1].name)
    }

    /** Caret on the `package` directive: must return [KaChangeSignatureComputer.Outcome.NotApplicable]. */
    fun testCompute_onPackageDirective_returnsNotApplicable() {
        val session = getSessionOrSkip() ?: return
        val computer = prepareComputer("notApplicable", session) ?: return

        val outcome = computer.compute()
        assertTrue(
            "Expected NotApplicable when caret is on package directive, got $outcome",
            outcome is KaChangeSignatureComputer.Outcome.NotApplicable,
        )
    }

    /**
     * Integration test (real K2): renames parameter `name` to `who` on function `greet`, declared
     * in `Source.kt` and called from `Usage.kt`. Verifies both the parameter's own body reference
     * ([org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinParameterUsage]) and
     * the call site's named argument (if any) are retargeted, exercising
     * [io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangeSignatureUsageSearchServiceImpl]'s
     * whole-project scan for the first time.
     */
    fun testApply_renameParameter_updatesBodyReferenceAndCallSite() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping rename-parameter test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-rename-param")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            Files.writeString(
                sourceFile,
                """
                package changesigtest.rename

                fun greet(name: String): String = "Hello, ${'$'}name"
                """.trimIndent()
            )
            val usageFile = tmpDir.resolve("Usage.kt")
            Files.writeString(
                usageFile,
                """
                package changesigtest.rename

                fun useGreet(): String = greet(name = "world")
                """.trimIndent()
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-rename-param",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val sourceKtFile = session.getKtFileForPath(sourceFile.toString()) ?: error("Failed to obtain source KtFile")
            val computer = KaChangeSignatureComputer(sourceKtFile, sourceKtFile.text.indexOf("greet"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = listOf(result.parameters[0].copy(name = "who")),
            )

            val applyOutcome = computer.apply(request)
            assertTrue(
                "Expected ApplyOutcome.Success, got $applyOutcome",
                applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success,
            )
            val success = applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success

            val sourcePath = sourceKtFile.virtualFile?.path ?: sourceKtFile.name
            val newSourceText = success.fileTexts[sourcePath]
            assertNotNull("Expected the source file to be among the touched files", newSourceText)
            // Accepts either "String" or "kotlin.String": shortenReferences() is expected to
            // shorten the fully-qualified type text updatePrimaryMethod() reconstructs from, but
            // whether it does so in every standalone session is a separate, pre-existing concern
            // (tracked for follow-up) — not what this test is verifying.
            assertTrue(
                "Expected parameter declaration renamed to 'who', got: $newSourceText",
                newSourceText!!.contains("fun greet(who: String)") || newSourceText.contains("fun greet(who: kotlin.String)"),
            )
            assertTrue(
                "Expected body reference '\$name' renamed to '\$who', got: $newSourceText",
                newSourceText.contains("\$who"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): reorders the two parameters of `greet`, declared in `Source.kt`
     * and called from `Usage.kt`. Verifies the call site's argument list is updated to match the
     * new parameter order (exercising [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinFunctionCallUsage]).
     */
    fun testApply_reorderParameters_updatesCallSite() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping reorder-parameters test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-reorder-params")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            Files.writeString(
                sourceFile,
                """
                package changesigtest.reorder

                fun greet(first: String, second: String): String = "${'$'}first ${'$'}second"
                """.trimIndent()
            )
            val usageFile = tmpDir.resolve("Usage.kt")
            Files.writeString(
                usageFile,
                """
                package changesigtest.reorder

                fun useGreet(): String = greet("alpha", "beta")
                """.trimIndent()
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-reorder-params",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val sourceKtFile = session.getKtFileForPath(sourceFile.toString()) ?: error("Failed to obtain source KtFile")
            val computer = KaChangeSignatureComputer(sourceKtFile, sourceKtFile.text.indexOf("greet"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = listOf(result.parameters[1], result.parameters[0]),
            )

            val applyOutcome = computer.apply(request)
            assertTrue(
                "Expected ApplyOutcome.Success, got $applyOutcome",
                applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success,
            )
            val success = applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success

            val usagePath = session.getKtFileForPath(usageFile.toString())?.virtualFile?.path
                ?: error("Failed to obtain usage KtFile path")
            val newUsageText = success.fileTexts[usagePath]
            assertNotNull("Expected the usage file to be among the touched files", newUsageText)
            assertTrue(
                "Expected call-site arguments reordered to (beta, alpha) or equivalent named form, got: $newUsageText",
                newUsageText!!.indexOf("beta") < newUsageText.indexOf("alpha"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): adds a new parameter (no default value) to `greet`, declared in
     * `Source.kt` and called from `Usage.kt`'s `useGreet`. Verifies "propagate to callers": since
     * `useGreet` directly calls `greet`, `useGreet` must itself grow the same new parameter and
     * forward it to the original call site — exercising [com.intellij.refactoring.changeSignature.CallerUsageInfo]
     * and [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinCallerCallUsage]
     * for the first time.
     */
    fun testApply_addParameter_propagatesToDirectCallers() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping propagate-to-callers test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-propagate-callers")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            Files.writeString(
                sourceFile,
                """
                package changesigtest.propagate

                fun greet(first: String): String = "Hello, ${'$'}first"
                """.trimIndent()
            )
            val usageFile = tmpDir.resolve("Usage.kt")
            Files.writeString(
                usageFile,
                """
                package changesigtest.propagate

                fun useGreet(): String = greet("world")
                """.trimIndent()
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-propagate-callers",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val sourceKtFile = session.getKtFileForPath(sourceFile.toString()) ?: error("Failed to obtain source KtFile")
            val computer = KaChangeSignatureComputer(sourceKtFile, sourceKtFile.text.indexOf("greet"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = result.parameters + KaChangeSignatureParameter(originalIndex = -1, name = "second", typeText = "String"),
            )

            val applyOutcome = computer.apply(request)
            assertTrue(
                "Expected ApplyOutcome.Success, got $applyOutcome",
                applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success,
            )
            val success = applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success

            val newSourceText = success.fileTexts[sourceKtFile.virtualFile?.path ?: sourceKtFile.name]
            assertNotNull("Expected the source file to be among the touched files", newSourceText)
            assertTrue(
                "Expected 'second' parameter added to greet's declaration, got: $newSourceText",
                newSourceText!!.contains("second"),
            )

            val usagePath = session.getKtFileForPath(usageFile.toString())?.virtualFile?.path
                ?: error("Failed to obtain usage KtFile path")
            val newUsageText = success.fileTexts[usagePath]
            assertNotNull("Expected the caller file (Usage.kt) to be among the touched files", newUsageText)
            // Accepts "String" or "kotlin.String" (pre-existing shortenReferences limitation,
            // see testApply_renameParameter's comment) and either spacing around the comma
            // (psiFactory-generated argument lists aren't re-run through the formatter here).
            assertTrue(
                "Expected useGreet (a direct caller) to grow a 'second' parameter of its own, got: $newUsageText",
                newUsageText!!.contains("fun useGreet(second: String)") ||
                    newUsageText.contains("fun useGreet(second: kotlin.String)"),
            )
            assertTrue(
                "Expected the original call site to forward the new parameter, got: $newUsageText",
                newUsageText.contains("greet(\"world\", second)") || newUsageText.contains("greet(\"world\",second)"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): regression test for a duplicate-parameter bug found during
     * manual testing — invoking Change Signature a *second* time and adding a new parameter with
     * the *same name* as one already propagated to `useGreet` by a prior invocation must not
     * duplicate it (`fun useGreet(second: String, second: String)`). Exercises the idempotency
     * guard in [io.github.nbplugins.kotlin.refactoring.KaChangeSignatureComputer.withCallerPropagation].
     */
    fun testApply_addSameParameterNameTwice_doesNotDuplicateOnCaller() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping duplicate-propagation regression test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-propagate-dedup")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            Files.writeString(
                sourceFile,
                """
                package changesigtest.propagatededup

                fun greet(first: String): String = "Hello, ${'$'}first"
                """.trimIndent()
            )
            val usageFile = tmpDir.resolve("Usage.kt")
            Files.writeString(
                usageFile,
                """
                package changesigtest.propagatededup

                fun useGreet(): String = greet("world")
                """.trimIndent()
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-propagate-dedup",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val sourceKtFile = session.getKtFileForPath(sourceFile.toString()) ?: error("Failed to obtain source KtFile")

            // First invocation: add "second" — propagates to useGreet (same as the test above).
            val computer1 = KaChangeSignatureComputer(sourceKtFile, sourceKtFile.text.indexOf("greet"))
            val result1 = (computer1.compute() as KaChangeSignatureComputer.Outcome.Ready).result
            val request1 = KaChangeSignatureRequest(
                newName = result1.declarationName,
                newReturnTypeText = result1.returnTypeText,
                parameters = result1.parameters + KaChangeSignatureParameter(originalIndex = -1, name = "second", typeText = "String"),
            )
            val applyOutcome1 = computer1.apply(request1)
            assertTrue(
                "Expected ApplyOutcome.Success, got $applyOutcome1",
                applyOutcome1 is KaChangeSignatureComputer.ApplyOutcome.Success,
            )

            // Production (KotlinChangeSignatureApplyElement) writes each touched file back to disk
            // and then calls KotlinAnalysisAPISession.invalidate() before any further action reuses
            // the project — mutating the in-memory KtFile via a second Computer without an
            // equivalent refresh leaves the K2 session's cached symbols out of sync with the
            // mutated PSI and fails analysis. Model that refresh here: persist the first apply's
            // result to disk and open a *fresh* session over it, exactly like a second, later
            // Ctrl+F6 invocation would see.
            for ((path, text) in (applyOutcome1 as KaChangeSignatureComputer.ApplyOutcome.Success).fileTexts) {
                Files.writeString(java.nio.file.Path.of(path), text)
            }
            val session2 = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-propagate-dedup-2",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val sourceKtFile2 = session2.getKtFileForPath(sourceFile.toString()) ?: error("Failed to obtain source KtFile (2nd session)")

            // Second invocation, on the freshly re-read declaration: user adds a *new* parameter
            // also named "second" (e.g. forgetting it was already added). greet's own descriptor
            // now correctly lists "second" as an *existing* parameter (originalIndex >= 0), but the
            // request below still asks for one more, brand-new "second" (originalIndex = -1) to
            // reproduce the exact user report.
            val computer2 = KaChangeSignatureComputer(sourceKtFile2, sourceKtFile2.text.indexOf("greet"))
            val result2 = (computer2.compute() as KaChangeSignatureComputer.Outcome.Ready).result
            val request2 = KaChangeSignatureRequest(
                newName = result2.declarationName,
                newReturnTypeText = result2.returnTypeText,
                parameters = result2.parameters + KaChangeSignatureParameter(originalIndex = -1, name = "second", typeText = "String"),
            )
            val applyOutcome2 = computer2.apply(request2)
            assertTrue(
                "Expected ApplyOutcome.Success, got $applyOutcome2",
                applyOutcome2 is KaChangeSignatureComputer.ApplyOutcome.Success,
            )
            val success2 = applyOutcome2 as KaChangeSignatureComputer.ApplyOutcome.Success

            val usagePath = session2.getKtFileForPath(usageFile.toString())?.virtualFile?.path
                ?: error("Failed to obtain usage KtFile path")
            val newUsageText = success2.fileTexts[usagePath]
            assertNotNull("Expected the caller file (Usage.kt) to be among the touched files", newUsageText)
            val secondOccurrences = Regex("""\bsecond\b""").findAll(newUsageText!!).count()
            assertEquals(
                "Expected useGreet to have exactly one 'second' parameter (not duplicated), got: $newUsageText",
                2, // one in the parameter declaration, one in the forwarded call-site argument
                secondOccurrences,
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
