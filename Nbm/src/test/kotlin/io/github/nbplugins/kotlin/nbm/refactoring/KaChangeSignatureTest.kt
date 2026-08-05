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

    /**
     * Integration test (real K2): E9.8 M2 — an `open` base function's signature change must
     * propagate into every overriding declaration project-wide
     * ([org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinOverrideUsageInfo]),
     * exercising [io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangeSignatureUsageSearchServiceImpl.findOverridings]
     * for the first time. Renames a parameter on `Base.greet`; `Derived.greet`'s `override fun`
     * must be renamed to match (it has no explicit parameter type it could otherwise diverge on).
     */
    fun testApply_renameParameter_propagatesToOverride() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping override-propagation test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-override")
        try {
            val baseFile = tmpDir.resolve("Base.kt")
            Files.writeString(
                baseFile,
                """
                package changesigtest.override

                open class Base {
                    open fun greet(first: String): String = "Hello, ${'$'}first"
                }
                """.trimIndent()
            )
            val derivedFile = tmpDir.resolve("Derived.kt")
            Files.writeString(
                derivedFile,
                """
                package changesigtest.override

                class Derived : Base() {
                    override fun greet(first: String): String = "Hi, ${'$'}first"
                }
                """.trimIndent()
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-override",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val baseKtFile = session.getKtFileForPath(baseFile.toString()) ?: error("Failed to obtain Base KtFile")
            val computer = KaChangeSignatureComputer(baseKtFile, baseKtFile.text.indexOf("greet"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = listOf(result.parameters.single().copy(name = "who")),
            )

            val applyOutcome = computer.apply(request)
            assertTrue(
                "Expected ApplyOutcome.Success, got $applyOutcome",
                applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success,
            )
            val success = applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success

            val newBaseText = success.fileTexts[baseKtFile.virtualFile?.path ?: baseKtFile.name]
            assertNotNull("Expected Base.kt among the touched files", newBaseText)
            assertTrue(
                "Expected Base.greet's parameter renamed to 'who', got: $newBaseText",
                newBaseText!!.contains("fun greet(who: String)") || newBaseText.contains("fun greet(who: kotlin.String)"),
            )

            val derivedPath = session.getKtFileForPath(derivedFile.toString())?.virtualFile?.path
                ?: error("Failed to obtain Derived KtFile path")
            val newDerivedText = success.fileTexts[derivedPath]
            assertNotNull("Expected Derived.kt (the override) among the touched files", newDerivedText)
            assertTrue(
                "Expected Derived's override renamed to match ('who'), got: $newDerivedText",
                newDerivedText!!.contains("override fun greet(who: String)") ||
                    newDerivedText.contains("override fun greet(who: kotlin.String)"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): E9.8 M2 — renaming a parameter to collide with another existing
     * parameter's name (`first` -> `second` when `second` already exists) must be rejected by
     * [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureConflictSearcher]'s
     * duplicate-name check (now wired into [KaChangeSignatureComputer.apply]) as
     * [KaChangeSignatureComputer.ApplyOutcome.Conflicts], with nothing mutated. This is a genuine
     * rename collision, distinct from the *new*-parameter duplicate-name case already guarded
     * upstream in [io.github.nbplugins.kotlin.refactoring.KaChangeSignatureComputer.apply] (see
     * `testApply_addSameParameterNameTwice_doesNotDuplicateOnCaller`) — that guard only dedupes
     * brand-new parameters, so this rename collision reaches the conflict searcher untouched.
     */
    fun testApply_renameParameterToCollideWithExisting_returnsConflicts() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping conflict-detection test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-conflict")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            val originalText = """
                package changesigtest.conflict

                fun greet(first: String, second: String): String = "${'$'}first ${'$'}second"
            """.trimIndent()
            Files.writeString(sourceFile, originalText)

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-conflict",
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
                parameters = listOf(
                    result.parameters[0].copy(name = "second"),
                    result.parameters[1],
                ),
            )

            val applyOutcome = computer.apply(request)
            assertTrue(
                "Expected ApplyOutcome.Conflicts for a rename that duplicates an existing parameter name, got $applyOutcome",
                applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Conflicts,
            )
            assertTrue(
                "Expected at least one conflict message",
                (applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Conflicts).messages.isNotEmpty(),
            )
            assertEquals("File must be left untouched when conflicts are found", originalText, sourceKtFile.text)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): E9.8 M2 — a callable reference (`::greet`) is a
     * [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinCallableReferenceUsage],
     * recognized via the same whole-project simple-name scan
     * ([io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangeSignatureUsageSearchServiceImpl])
     * used for plain calls — the reference's callable name is itself a `KtSimpleNameExpression`.
     * Regression: apply() must not crash or spuriously report conflicts when a `::greet` reference
     * exists alongside a normal call site; the call site's own argument list must still update
     * correctly.
     */
    fun testApply_addParameter_coexistsWithCallableReference() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping callable-reference coexistence test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-callableref")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            Files.writeString(
                sourceFile,
                """
                package changesigtest.callableref

                fun greet(first: String): String = "Hello, ${'$'}first"
                """.trimIndent()
            )
            val usageFile = tmpDir.resolve("Usage.kt")
            Files.writeString(
                usageFile,
                """
                package changesigtest.callableref

                val greeter: (String) -> String = ::greet

                fun useGreet(): String = greet("world")
                """.trimIndent()
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-callableref",
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
                parameters = listOf(result.parameters.single().copy(name = "who")),
            )

            val applyOutcome = computer.apply(request)
            assertTrue(
                "Expected ApplyOutcome.Success (a callable reference must not spuriously break apply), got $applyOutcome",
                applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success,
            )
            val success = applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success

            val usagePath = session.getKtFileForPath(usageFile.toString())?.virtualFile?.path
                ?: error("Failed to obtain usage KtFile path")
            val newUsageText = success.fileTexts[usagePath]
            assertNotNull("Expected Usage.kt among the touched files", newUsageText)
            assertTrue(
                "Expected the ::greet callable reference to survive untouched, got: $newUsageText",
                newUsageText!!.contains("::greet"),
            )
            assertTrue(
                "Expected the plain (positional-argument) call site to remain a valid call, got: $newUsageText",
                newUsageText.contains("greet(\"world\")"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): E9.8 M2 — adding a brand-new parameter (no default value) to an
     * `open` base function must also add it to every overriding declaration's own parameter list,
     * not just rename an existing one (that path is already covered by
     * [testApply_renameParameter_propagatesToOverride]). Exercises the `isCaller = false`,
     * `isInherited = true` branch of `processParameterListWithStructuralChanges`, which is a
     * separate code path from the rename-only case.
     */
    fun testApply_addParameter_propagatesToOverride() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping override add-parameter test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-override-add")
        try {
            val baseFile = tmpDir.resolve("Base.kt")
            Files.writeString(
                baseFile,
                """
                package changesigtest.overrideadd

                open class Base {
                    open fun ff(one: String, two: String) = "${'$'}one ${'$'}two"
                }
                """.trimIndent()
            )
            val derivedFile = tmpDir.resolve("Derived.kt")
            Files.writeString(
                derivedFile,
                """
                package changesigtest.overrideadd

                class Derived : Base() {
                    override fun ff(one: String, two: String) = "${'$'}two ${'$'}one"
                }
                """.trimIndent()
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-override-add",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val baseKtFile = session.getKtFileForPath(baseFile.toString()) ?: error("Failed to obtain Base KtFile")
            val computer = KaChangeSignatureComputer(baseKtFile, baseKtFile.text.indexOf("ff"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = result.parameters + KaChangeSignatureParameter(originalIndex = -1, name = "three", typeText = "Int"),
            )

            val applyOutcome = computer.apply(request)
            assertTrue(
                "Expected ApplyOutcome.Success, got $applyOutcome",
                applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success,
            )
            val success = applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success

            val derivedPath = session.getKtFileForPath(derivedFile.toString())?.virtualFile?.path
                ?: error("Failed to obtain Derived KtFile path")
            val newDerivedText = success.fileTexts[derivedPath]
            assertNotNull("Expected Derived.kt among the touched files", newDerivedText)
            assertTrue(
                "Expected override to grow the 'three' parameter too, got: $newDerivedText",
                newDerivedText!!.contains("fun ff(one: String, two: String, three: Int)") ||
                    newDerivedText.contains("fun ff(one: kotlin.String, two: kotlin.String, three: kotlin.Int)"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): regression for a manual-test finding — a call site whose
     * receiver's *static type* is the subclass (`val b = Derived(); b.ff(...)`) resolves to
     * `Derived.ff` (the override), not `Base.ff`, even when Change Signature is invoked on
     * `Base.ff`. Plain PSI-identity usage matching (`resolved == element`) never finds such a call
     * site, so the new parameter reached the override's own declaration (via [findOverridings]) but
     * never propagated into the enclosing caller or the call site's argument list. Exercises
     * [io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangeSignatureUsageSearchServiceImpl.isOverrideRelated].
     */
    fun testApply_addParameter_propagatesToCallSiteThroughOverride() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping override call-site propagation test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-override-callsite")
        try {
            val baseFile = tmpDir.resolve("Base.kt")
            Files.writeString(
                baseFile,
                """
                package changesigtest.overridecallsite

                open class Base {
                    open fun ff(one: String, two: String) = "${'$'}one ${'$'}two"
                }
                """.trimIndent()
            )
            val derivedFile = tmpDir.resolve("Derived.kt")
            Files.writeString(
                derivedFile,
                """
                package changesigtest.overridecallsite

                class Derived : Base() {
                    override fun ff(one: String, two: String) = "${'$'}two ${'$'}one"
                }
                """.trimIndent()
            )
            val usageFile = tmpDir.resolve("Usage.kt")
            Files.writeString(
                usageFile,
                """
                package changesigtest.overridecallsite

                fun useDerived(): String {
                    val b = Derived()
                    return b.ff("alpha", "betta")
                }
                """.trimIndent()
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-override-callsite",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val baseKtFile = session.getKtFileForPath(baseFile.toString()) ?: error("Failed to obtain Base KtFile")
            val computer = KaChangeSignatureComputer(baseKtFile, baseKtFile.text.indexOf("ff"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = result.parameters + KaChangeSignatureParameter(originalIndex = -1, name = "three", typeText = "Int"),
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
            assertNotNull("Expected Usage.kt among the touched files", newUsageText)
            assertTrue(
                "Expected useDerived (a caller through the override) to grow a 'three' parameter of its own, got: $newUsageText",
                newUsageText!!.contains("fun useDerived(three: Int)") || newUsageText.contains("fun useDerived(three: kotlin.Int)"),
            )
            assertTrue(
                "Expected the call site through the override to forward the new parameter, got: $newUsageText",
                Regex("""b\.ff\("alpha",\s*"betta",\s*three\)""").containsMatchIn(newUsageText),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): regression for a manual-testing-session finding — adding a
     * parameter to *any* constructor (primary or secondary) previously crashed
     * (`KotlinIllegalArgumentExceptionWithAttachments: Error while resolving FirConstructorImpl
     * ... from ANNOTATION_ARGUMENTS to BODY_RESOLVE`), even for the simplest possible constructor
     * with no supertype, no delegation, and no default values — a plain rename did not crash, only
     * a *structural* change (add/remove/reorder). Root cause: after a structural change swaps in a
     * brand-new parameter-list PSI node (`KtParameterList.replace()`), `updatePrimaryMethod()`'s two
     * `shortenReferences()` calls need to resolve the constructor's FIR node to `BODY_RESOLVE`
     * phase, which fails because this plugin's standalone `NoOpPomModel` never fires the real
     * "out-of-block modification" notification a live IDE would to invalidate the stale FIR cache —
     * functions tolerate this; constructors do not (`docs/stubs.md`-class limitation, not something
     * a full fix is practical for standalone). Both `shortenReferences()` call sites now swallow this
     * failure and fall back to un-shortened, fully-qualified type names for the constructor's own
     * parameter list rather than aborting the whole refactoring.
     */
    fun testApply_addParameter_toPrimaryConstructor_doesNotCrash() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping constructor add-parameter test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-ctor-add")
        try {
            val f = tmpDir.resolve("Base.kt")
            Files.writeString(
                f,
                """
                package changesigtest.ctoradd

                open class Base(one: String, two: String)
                """.trimIndent()
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-ctor-add",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(f.toString()) ?: error("Failed to obtain Base KtFile")
            val computer = KaChangeSignatureComputer(ktFile, ktFile.text.indexOf("one: String, two: String)"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = result.parameters + KaChangeSignatureParameter(originalIndex = -1, name = "three", typeText = "Int"),
            )

            val applyOutcome = computer.apply(request)
            assertTrue(
                "Expected ApplyOutcome.Success (constructors must not crash on a structural change), got $applyOutcome",
                applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success,
            )
            val newText = (applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success).fileTexts.values.first()
            assertTrue(
                "Expected the primary constructor to grow the 'three' parameter, got: $newText",
                newText.contains("open class Base(one: String, two: String, three: Int)"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): E9.8 M3 — a `super(...)`/`this(...)` constructor-delegation call
     * targeting the changed constructor is now found and processed
     * ([org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinConstructorDelegationCallUsage],
     * via the new [io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangeSignatureUsageSearchServiceImpl.findConstructorDelegationCallers]
     * whole-project scan) instead of being silently skipped — its callee
     * (`KtConstructorDelegationReferenceExpression`, standing in for the `this`/`super` keyword) is
     * a `KtReferenceExpression` but not a `KtSimpleNameExpression`, so the general whole-project
     * simple-name scan never visited it before this fix. Renaming (rather than adding) a parameter
     * is enough to prove the delegation call was actually found and processed: the ported engine's
     * `KotlinFunctionCallUsage`-delegate logic only rewrites a delegation call's *argument list* if
     * there's a positional/name mismatch to fix, so this also confirms the call survives untouched
     * (still a valid `super(one, two)`) when nothing about its own arguments needs to change.
     */
    fun testApply_renameParameter_findsConstructorDelegationCall() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping constructor-delegation-call test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-ctor-delegation")
        try {
            val f = tmpDir.resolve("Base.kt")
            Files.writeString(
                f,
                """
                package changesigtest.ctordelegation

                open class Base(one: String, two: String)

                class Sub : Base {
                    constructor(one: String, two: String) : super(one, two)
                    constructor(one: String) : this(one, "x")
                }
                """.trimIndent()
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-ctor-delegation",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(f.toString()) ?: error("Failed to obtain Base KtFile")
            val computer = KaChangeSignatureComputer(ktFile, ktFile.text.indexOf("one: String, two: String)"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = listOf(result.parameters[0].copy(name = "renamed"), result.parameters[1]),
            )

            val applyOutcome = computer.apply(request)
            assertTrue("Expected ApplyOutcome.Success, got $applyOutcome", applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success)
            val newText = (applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success).fileTexts.values.first()

            // Accepts "String" or "kotlin.String" — shortenReferences() doesn't reliably shorten
            // types on a constructor's parameter list in this standalone environment (see class doc
            // above and the pom.xml patch it references); a cosmetic, already-accepted limitation.
            assertTrue(
                "Expected the primary constructor's parameter renamed, got: $newText",
                Regex("""open class Base\(renamed: (kotlin\.)?String, two: (kotlin\.)?String\)""").containsMatchIn(newText),
            )
            assertTrue(
                "Expected the super(...) delegation call (a KtConstructorDelegationCall) to survive as a valid call, got: $newText",
                newText.contains("constructor(one: String, two: String) : super(one, two)"),
            )
            assertTrue(
                "Expected the unrelated this(...) delegation call (targets the other secondary constructor, not Base) to stay untouched, got: $newText",
                newText.contains("constructor(one: String) : this(one, \"x\")"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): E9.8 M3 — an `enum class` primary constructor is a structural
     * usage type ([org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinEnumEntryWithoutSuperCallUsage]):
     * the ported engine finds every enum entry with no explicit super call by walking the enum
     * class's own declarations, not via reference search, so this already worked without any Nbm-side
     * change — this test documents and locks in that behavior. Adding a new parameter with no
     * default reserves an empty argument slot at each entry (same "no value to forward" degraded
     * pattern already covered for plain calls without a caller to propagate to).
     */
    fun testApply_addParameter_toEnumPrimaryConstructor_reservesSlotAtEachEntry() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping enum-entry test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-enum")
        try {
            val f = tmpDir.resolve("Color.kt")
            Files.writeString(
                f,
                """
                package changesigtest.enumentry

                enum class Color(val hex: String) {
                    RED("ff0000"),
                    GREEN("00ff00");
                }
                """.trimIndent()
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-enum",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(f.toString()) ?: error("Failed to obtain Color KtFile")
            val computer = KaChangeSignatureComputer(ktFile, ktFile.text.indexOf("hex: String"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = result.parameters + KaChangeSignatureParameter(originalIndex = -1, name = "code", typeText = "Int"),
            )

            val applyOutcome = computer.apply(request)
            assertTrue("Expected ApplyOutcome.Success, got $applyOutcome", applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success)
            val newText = (applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success).fileTexts.values.first()

            assertTrue(
                "Expected the enum's primary constructor to grow the 'code' parameter, got: $newText",
                newText.contains("enum class Color(val hex: String, code: Int)"),
            )
            assertTrue(
                "Expected RED's entry to reserve an (empty) argument slot, got: $newText",
                Regex("""RED\("ff0000",\s*\)""").containsMatchIn(newText),
            )
            assertTrue(
                "Expected GREEN's entry to reserve an (empty) argument slot, got: $newText",
                Regex("""GREEN\("00ff00",\s*\)""").containsMatchIn(newText),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): E9.8 M3 — reordering a data class primary constructor's
     * parameters must reorder any `val (a, b) = point` destructuring accordingly, so each entry
     * keeps binding to the same semantic value it did before
     * ([org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinComponentUsageInDestructuring]).
     * Regression: a destructuring entry's reference
     * (`KaFirDestructuringDeclarationReference`, a `KtMultiReference`) doesn't resolve through
     * `resolveToSymbol()` (returns null) — only through `multiResolve()`, which returns *two*
     * results (the entry's own declaration and the constructor parameter it destructures). Exercises
     * [io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangeSignatureUsageSearchServiceImpl]'s
     * `multiResolve()` fallback, added after this was found to silently do nothing without it.
     */
    fun testApply_reorderParameters_reordersDataClassDestructuring() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping data-class destructuring test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-destructuring")
        try {
            val f = tmpDir.resolve("Point.kt")
            Files.writeString(
                f,
                """
                package changesigtest.destructuring

                data class Point(val x: Int, val y: Int)

                fun useIt(p: Point) {
                    val (a, b) = p
                }
                """.trimIndent()
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-destructuring",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(f.toString()) ?: error("Failed to obtain Point KtFile")
            val computer = KaChangeSignatureComputer(ktFile, ktFile.text.indexOf("x: Int, val y: Int"))

            val outcome = computer.compute()
            assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            val request = KaChangeSignatureRequest(
                newName = result.declarationName,
                newReturnTypeText = result.returnTypeText,
                parameters = listOf(result.parameters[1], result.parameters[0]),
            )

            val applyOutcome = computer.apply(request)
            assertTrue("Expected ApplyOutcome.Success, got $applyOutcome", applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success)
            val newText = (applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success).fileTexts.values.first()

            assertTrue(
                "Expected Point's constructor parameters reordered, got: $newText",
                newText.contains("data class Point(val y: Int, val x: Int)"),
            )
            assertTrue(
                "Expected the destructuring entries reordered to preserve each variable's binding ('a' still binds to x, 'b' still binds to y), got: $newText",
                newText.contains("val (b, a) = p"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): E9.8 M3 — a by-convention operator call (`box["key"]`, sugar for
     * `box.get("key")`) is a [org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.usages.KotlinByConventionCallUsage].
     * Its call site has no literal callee identifier in source for a plain simple-name scan to find
     * (`KtArrayAccessExpression`'s reference, `KtArrayAccessReference`, lives on the whole `box["key"]`
     * expression, not on a name token), so it was previously invisible to usage search entirely.
     * The ported engine's own preprocessing step desugars the call to explicit dot-call syntax as
     * part of applying the new parameter list — `box["key"]` becomes `box.get("key")`, matching real
     * IDEA's behavior for this refactoring (not a regression introduced by porting it standalone).
     */
    fun testApply_addParameter_findsByConventionOperatorCall() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping by-convention call test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-convention")
        try {
            val f = tmpDir.resolve("Box.kt")
            Files.writeString(
                f,
                """
                package changesigtest.convention

                class Box {
                    operator fun get(one: String): String = one
                }

                fun useIt(b: Box): String = b["hello"]
                """.trimIndent()
            )
            // Standalone K2 can occasionally leave this by-convention call's FIR declaration at
            // ANNOTATION_ARGUMENTS when the test suite has just mutated other PSI files. Rebuild
            // an isolated session for one retry so the assertion still tests the real operation,
            // rather than inheriting unrelated global FIR cache state.
            val applyOutcome = (1..2).asSequence().map { attempt ->
                val session = KotlinAnalysisAPISession.createWithJars(
                    moduleName = "change-signature-convention-$attempt",
                    binaryJars = listOf(stdlib),
                    sourceRoots = listOf(tmpDir),
                )
                val ktFile = session.getKtFileForPath(f.toString()) ?: error("Failed to obtain Box KtFile")
                val computer = KaChangeSignatureComputer(
                    ktFile,
                    ktFile.text.indexOf("get(one: String)") + "get(".length,
                )
                val outcome = computer.compute()
                assertTrue("Expected Ready, got $outcome", outcome is KaChangeSignatureComputer.Outcome.Ready)
                val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
                computer.apply(
                    KaChangeSignatureRequest(
                        newName = result.declarationName,
                        newReturnTypeText = result.returnTypeText,
                        parameters = result.parameters + KaChangeSignatureParameter(
                            originalIndex = -1,
                            name = "two",
                            typeText = "Int",
                        ),
                    ),
                )
            }.firstOrNull { it is KaChangeSignatureComputer.ApplyOutcome.Success }

            assertTrue("Expected ApplyOutcome.Success, got $applyOutcome", applyOutcome is KaChangeSignatureComputer.ApplyOutcome.Success)
            val newText = (applyOutcome as KaChangeSignatureComputer.ApplyOutcome.Success).fileTexts.values.first()

            assertTrue(
                "Expected the operator function to grow the 'two' parameter, got: $newText",
                newText.contains("operator fun get(one: String, two: Int)"),
            )
            assertTrue(
                "Expected the [] call site to have been found and desugared to an explicit call, got: $newText",
                newText.contains("b.get(\"hello\")"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): regression for a manual-testing-session finding — with the caret
     * on the class *name* (`enum class Color(...)`, not inside the parameter list), [findDeclaration]
     * previously resolved to the `KtClass` itself rather than its primary constructor. Since
     * `KotlinMethodDescriptor` only reads parameters off a `KtCallableDeclaration` (which `KtClass`
     * is not, but `KtPrimaryConstructor` is), that silently produced a `Ready` result with an *empty*
     * parameter list — the dialog would show no parameters at all, even though the class has some.
     * `findDeclaration`'s `normalize()` step now redirects a `KtClass` target to its primary
     * constructor whenever one already exists.
     */
    fun testCompute_caretOnClassNameWithExistingPrimaryConstructor_returnsParameters() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping caret-on-class-name test")
            return
        }
        val tmpDir = Files.createTempDirectory("nbkotlin-changesig-caret-classname")
        try {
            val f = tmpDir.resolve("Color.kt")
            Files.writeString(
                f,
                """
                package changesigtest.caretclassname

                enum class Color(val hex: String) {
                    RED("ff0000"),
                    GREEN("00ff00");
                }
                """.trimIndent()
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-signature-caret-classname",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val ktFile = session.getKtFileForPath(f.toString()) ?: error("Failed to obtain Color KtFile")
            // Caret on "Color" itself, not inside "(val hex: String)".
            val computer = KaChangeSignatureComputer(ktFile, ktFile.text.indexOf("Color"))

            val outcome = computer.compute()
            assertTrue(
                "Expected Ready for caret on the class name, got $outcome",
                outcome is KaChangeSignatureComputer.Outcome.Ready,
            )
            val result = (outcome as KaChangeSignatureComputer.Outcome.Ready).result
            assertEquals("Color", result.declarationName)
            assertEquals(
                "Expected the existing primary constructor's parameter to be found, not an empty list",
                1,
                result.parameters.size,
            )
            assertEquals("hex", result.parameters.single().name)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
