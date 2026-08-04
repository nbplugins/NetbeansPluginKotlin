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
import io.github.nbplugins.kotlin.refactoring.KaMoveDeclarationComputer
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaMoveDeclarationComputer].
 *
 * Fixtures are in `projForTest/src/moveDeclaration/`. Each sub-directory contains:
 *  - `file.kt`    — Kotlin source being refactored
 *  - `file.caret` — same source with `<caret>` marking the caret position
 */
class KaMoveDeclarationTest : KotlinTestCase("KaMoveDeclarationTest", "moveDeclaration") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaMoveDeclarationTest: skipping — no K2 dependencies available")
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
    ): Pair<KaMoveDeclarationComputer, KtFile>? {
        val fileFo = dir.getFileObject(subDir)?.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaMoveDeclarationComputer(ktFile, offset) to ktFile
    }

    private fun findStdlibJar(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map { Path.of(it) }
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    // -----------------------------------------------------------------------
    // Computer correctness tests
    // -----------------------------------------------------------------------

    /**
     * Caret inside top-level function `greet`: the computer must return
     * [KaMoveDeclarationComputer.Outcome.Ready] with a correctly extracted name/package.
     */
    fun testTopLevelFunction_returnsReady() {
        val session = getSessionOrSkip() ?: return
        val (computer, ktFile) = prepareComputer("topLevel", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready for caret inside top-level function, got $outcome",
            outcome is KaMoveDeclarationComputer.Outcome.Ready,
        )
        val result = (outcome as KaMoveDeclarationComputer.Outcome.Ready).result
        assertEquals("greet", result.declarationName)
        assertEquals("${result.declarationName}.kt", result.suggestedFileName)
        assertEquals(ktFile.packageFqName.asString(), result.packageName)
    }

    /**
     * Caret on the `package` directive (not inside any named declaration): the computer must
     * return [KaMoveDeclarationComputer.Outcome.NotApplicable].
     */
    fun testNotApplicable_onPackageDirective() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("notApplicable", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable when caret is on package directive, got $outcome",
            outcome is KaMoveDeclarationComputer.Outcome.NotApplicable,
        )
    }

    // -----------------------------------------------------------------------
    // Integration test with a real K2 session: the actual move + usage retargeting
    // -----------------------------------------------------------------------

    /**
     * Integration test (real K2): moves top-level function `greet` from `Source.kt` into a new
     * package/file, and verifies:
     *  1. The declaration is removed from the source file.
     *  2. The target file is created with the declaration under the new package.
     *  3. A usage of `greet` in a third file is found by the real project-wide search
     *     ([io.github.nbplugins.kotlin.nbm.refactoring.KotlinMoveUsageSearchServiceImpl]) and
     *     retargeted to the new package via `K2MoveRenameUsageInfo.retargetUsages` — exercising the
     *     external (cross-file) retargeting path for the first time in this codebase (Copy
     *     Declaration only ever retargets same-file/internal usages); relies on
     *     [io.github.nbplugins.kotlin.nbm.resolve.LenientLoggerFactory] being installed so a
     *     platform `LOG.error()` call inside `K2ReferenceMutateService` logs instead of throwing.
     */
    fun testMove_realSession_movesDeclarationAndRetargetsUsage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping move integration test")
            return
        }

        val tmpDir = Files.createTempDirectory("nbkotlin-move-decl")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            Files.writeString(
                sourceFile,
                """
                package movetest.source

                fun greet(name: String): String = "Hello, ${'$'}name"
                """.trimIndent()
            )
            val usageFile = tmpDir.resolve("Usage.kt")
            Files.writeString(
                usageFile,
                """
                package movetest.usage

                import movetest.source.greet

                fun useGreet(): String = greet("world")
                """.trimIndent()
            )
            // The destination directory mirrors the target package beneath the session's source
            // root, as KotlinMoveDeclarationApplyElement does through KotlinPackageTarget.
            val targetDir = tmpDir.resolve("movetest/target")
            Files.createDirectories(targetDir)
            // The target file must already exist (seeded with its package directive) before
            // calling move() — this environment's disk-backed VFS is read-only for writes.
            val targetFile = targetDir.resolve("Target.kt")
            Files.writeString(targetFile, "package movetest.target\n\n")

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-declaration-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val sourceKtFile = session.getKtFileForPath(sourceFile.toString())
                ?: error("Failed to obtain source KtFile")
            // The target file was written to disk before the session was created, so scanSourceFiles
            // already picked it up as a writable, LightVirtualFile-backed KtFile (same as the source).
            val targetKtFile = session.getKtFileForPath(targetFile.toString())
                ?: error("Failed to obtain target KtFile")

            val computer = KaMoveDeclarationComputer(sourceKtFile, sourceKtFile.text.indexOf("greet"))
            val targetPsiDirectory = KaMoveDeclarationComputer.resolveDirectory(sourceKtFile.project, targetDir.toString())
                ?: run {
                    println("Could not resolve a real PsiDirectory for $targetDir — skipping move integration test")
                    return
                }

            val outcome = computer.move(targetPsiDirectory, targetKtFile, FqName("movetest.target"))
            assertNotNull("Expected a move outcome", outcome)
            assertTrue(
                "Expected MoveOutcome.Success, got $outcome",
                outcome is KaMoveDeclarationComputer.MoveOutcome.Success,
            )
            val success = outcome as KaMoveDeclarationComputer.MoveOutcome.Success

            assertFalse(
                "Expected 'fun greet' to be removed from the source file, got: ${success.sourceText}",
                success.sourceText.contains("fun greet"),
            )
            assertTrue(
                "Expected target text to contain the moved function, got: ${success.targetText}",
                success.targetText.contains("fun greet"),
            )
            assertEquals(
                "Expected source, target, and external usage files to be returned for persistence",
                setOf(sourceFile.toString(), targetFile.toString(), usageFile.toString()),
                success.changedFiles.keys,
            )

            // Persist every changed file via plain I/O, mirroring the NetBeans adapter. Persisting
            // only source and target would discard the engine's usage import retargeting.
            success.changedFiles.forEach { (path, text) -> Files.writeString(Path.of(path), text) }

            val targetText = Files.readString(targetFile)
            assertTrue(
                "Expected target file to contain the moved function, got: $targetText",
                targetText.contains("fun greet"),
            )

            val usageTextAfterMove = Files.readString(usageFile)
            assertTrue(
                "Expected persisted usage import to be retargeted to the new package, got: $usageTextAfterMove",
                usageTextAfterMove.contains("import movetest.target.greet"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): moving into a target which already has a declaration must
     * preserve a blank-line boundary between the existing and moved declarations. The standalone
     * PSI target does not receive IDEA editor whitespace normalization after `KtFile.add`.
     */
    fun testMove_realSession_existingTargetSeparatesDeclarations() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping move integration test")
            return
        }

        val tmpDir = Files.createTempDirectory("nbkotlin-move-decl-existing-target")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            Files.writeString(
                sourceFile,
                """
                package movetest.source

                fun greet(who: String, second: String): String = "${'$'}who ${'$'}second"
                """.trimIndent(),
            )
            val targetDir = tmpDir.resolve("movetest/target")
            Files.createDirectories(targetDir)
            val targetFile = targetDir.resolve("Target.kt")
            Files.writeString(
                targetFile,
                """
                package movetest.target

                fun unrelated(): Int = 42
                """.trimIndent(),
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-declaration-existing-target",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val sourceKtFile = session.getKtFileForPath(sourceFile.toString())
                ?: error("Failed to obtain source KtFile")
            val targetKtFile = session.getKtFileForPath(targetFile.toString())
                ?: error("Failed to obtain target KtFile")
            val targetPsiDirectory = KaMoveDeclarationComputer.resolveDirectory(sourceKtFile.project, targetDir.toString())
                ?: error("Failed to resolve target PsiDirectory")

            val outcome = KaMoveDeclarationComputer(sourceKtFile, sourceKtFile.text.indexOf("greet"))
                .move(targetPsiDirectory, targetKtFile, FqName("movetest.target"))
            assertTrue("Expected MoveOutcome.Success, got $outcome", outcome is KaMoveDeclarationComputer.MoveOutcome.Success)
            val success = outcome as KaMoveDeclarationComputer.MoveOutcome.Success
            success.changedFiles.forEach { (path, text) -> Files.writeString(Path.of(path), text) }

            val targetText = Files.readString(targetFile)
            assertTrue("Expected existing declaration to remain, got: $targetText", targetText.contains("fun unrelated(): Int = 42"))
            assertTrue("Expected moved declaration to be separated, got: $targetText", targetText.contains("42\n\nfun greet"))
            assertFalse("Target declarations must not be concatenated, got: $targetText", targetText.contains("42fun greet"))
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test (real K2): the caret is on the **call site** `greet("world")` in a
     * different file (`Usage.kt`), not on the declaration itself. Per this project's
     * "cursor-on-declaration-or-usage" convention (see `docs/development-plan.md`'s E9 strategy
     * section), [KaMoveDeclarationComputer.compute] must still resolve the declaring function
     * (in `Source.kt`) via K2 reference resolution, and [KaMoveDeclarationComputer.move] must
     * still move the *declaration's own file*, not the file the caret happened to be in.
     */
    fun testCompute_caretOnUsageInDifferentFile_resolvesDeclaration() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping caret-on-usage test")
            return
        }

        val tmpDir = Files.createTempDirectory("nbkotlin-move-decl-usage-caret")
        try {
            val sourceFile = tmpDir.resolve("Source.kt")
            Files.writeString(
                sourceFile,
                """
                package movetest.source2

                fun greet(name: String): String = "Hello, ${'$'}name"
                """.trimIndent()
            )
            val usageFile = tmpDir.resolve("Usage.kt")
            val usageText = """
                package movetest.usage2

                import movetest.source2.greet

                fun useGreet(): String = greet("world")
                """.trimIndent()
            Files.writeString(usageFile, usageText)

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-declaration-caret-on-usage",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(tmpDir),
            )
            val usageKtFile = session.getKtFileForPath(usageFile.toString())
                ?: error("Failed to obtain usage KtFile")
            val sourceKtFile = session.getKtFileForPath(sourceFile.toString())
                ?: error("Failed to obtain source KtFile")

            // Caret on the "greet" identifier at the call site, not the declaration.
            val callOffset = usageText.lastIndexOf("greet(")
            val computer = KaMoveDeclarationComputer(usageKtFile, callOffset)

            val outcome = computer.compute()
            assertTrue(
                "Expected Ready when caret is on a call site, got $outcome",
                outcome is KaMoveDeclarationComputer.Outcome.Ready,
            )
            val result = (outcome as KaMoveDeclarationComputer.Outcome.Ready).result
            assertEquals("greet", result.declarationName)
            assertEquals(
                "Expected sourceFilePath to point at the declaration's own file, not the usage file",
                sourceKtFile.virtualFile?.path,
                result.sourceFilePath,
            )
            assertEquals("movetest.source2", result.packageName)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
