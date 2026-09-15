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
import io.github.nbplugins.kotlin.refactoring.KaMoveDeclarationComputer
import io.github.nbplugins.kotlin.refactoring.KaMoveFileComputer
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaMoveFileComputer].
 *
 * Fixtures are in `projForTest/src/moveFile/`. Each subdirectory contains a Kotlin file whose
 * source-root-relative package layout is used to verify Move File discovery.
 */
class KaMoveFileComputerTest : KotlinTestCase("KaMoveFileComputerTest", "moveFile") {

    /**
     * Verifies that a Kotlin source file whose package matches its source-root-relative directory
     * is accepted and declares that its package may be updated after a physical move.
     */
    fun testCompute_matchingPackageDirectory_returnsReadyWithPackageUpdate() {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaMoveFileComputerTest: skipping — no K2 dependencies available")
            return
        }
        val file = dir.getFileObject("matching.kt") ?: error("Missing matching.kt fixture")
        val ktFile = session.getKtFileForPath(file.path) ?: error("Could not obtain matching.kt PSI")

        val outcome = KaMoveFileComputer(ktFile).compute()

        assertTrue("Expected Ready for a Kotlin source file, got $outcome", outcome is KaMoveFileComputer.Outcome.Ready)
        val result = (outcome as KaMoveFileComputer.Outcome.Ready).result
        assertEquals(file.path, result.sourceFilePath)
        assertEquals("moveFile", result.packageName)
        assertTrue("Matching source-root/package layout must permit a package update", result.packageMayBeUpdated)
    }

    /**
     * Verifies that a Kotlin source file with a mismatched package and directory remains movable,
     * but Move File explicitly preserves its package rather than applying a guessed rewrite.
     */
    fun testCompute_mismatchedPackageDirectory_preservesPackage() {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaMoveFileComputerTest: skipping — no K2 dependencies available")
            return
        }
        val file = dir.getFileObject("mismatch.kt") ?: error("Missing mismatch.kt fixture")
        val ktFile = session.getKtFileForPath(file.path) ?: error("Could not obtain mismatch.kt PSI")

        val outcome = KaMoveFileComputer(ktFile).compute()

        assertTrue("Expected Ready for a Kotlin source file, got $outcome", outcome is KaMoveFileComputer.Outcome.Ready)
        val result = (outcome as KaMoveFileComputer.Outcome.Ready).result
        assertEquals("different.package", result.packageName)
        assertFalse("Mismatched source-root/package layout must preserve the declared package", result.packageMayBeUpdated)
    }

    /**
     * Verifies the lifecycle-free port invokes IDEA's K2MoveFilesHandler semantics: a matching
     * package is rewritten for the destination and no full IDEA file-move processor is required.
     */
    fun testApply_realSession_updatesEligiblePackage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Move File integration test")
            return
        }
        val temp = Files.createTempDirectory("nbkotlin-move-file")
        try {
            val sourceDirectory = temp.resolve("move/source")
            val targetDirectory = temp.resolve("move/target")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(targetDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            Files.writeString(sourcePath, "package move.source\\n\\nfun moved() = 1\\n")

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-file-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temp),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val targetPsi = KaMoveDeclarationComputer.resolveDirectory(source.project, targetDirectory.toString())
                ?: error("Could not resolve target directory PSI")

            val outcome = KaMoveFileComputer(source).apply(
                files = listOf(source),
                targetDirectory = targetPsi,
                targetPackage = org.jetbrains.kotlin.name.FqName("move.target"),
                updateReferences = true,
            )

            assertTrue("Expected K2 Move File success, got $outcome", outcome is KaMoveFileComputer.ApplyOutcome.Success)
            val success = outcome as KaMoveFileComputer.ApplyOutcome.Success
            assertTrue(
                "Expected package directive rewritten by K2MoveFilesHandler, got ${success.changedFiles[sourcePath.toString()]}",
                success.changedFiles[sourcePath.toString()]?.contains("package move.target") == true,
            )
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an external Kotlin import is returned for persistence after Move File retargeting.
     */
    fun testApply_realSession_returnsRetargetedUsageImport() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Move File integration test")
            return
        }
        val temp = Files.createTempDirectory("nbkotlin-move-file-usage")
        try {
            val sourceDirectory = temp.resolve("move/source")
            val targetDirectory = temp.resolve("move/target")
            val usageDirectory = temp.resolve("move/usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(targetDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(sourcePath, "package move.source\n\nfun moved() = 1\n")
            Files.writeString(
                usagePath,
                "package move.usage\n\nimport move.source.moved\n\nfun useMoved() = moved()\n",
            )

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-file-usage-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temp),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val targetPsi = KaMoveDeclarationComputer.resolveDirectory(source.project, targetDirectory.toString())
                ?: error("Could not resolve target directory PSI")

            val outcome = KaMoveFileComputer(source).apply(
                files = listOf(source),
                targetDirectory = targetPsi,
                targetPackage = org.jetbrains.kotlin.name.FqName("move.target"),
                updateReferences = true,
            )

            assertTrue("Expected K2 Move File success, got $outcome", outcome is KaMoveFileComputer.ApplyOutcome.Success)
            val success = outcome as KaMoveFileComputer.ApplyOutcome.Success
            assertTrue(
                "Expected changed files to include the retargeted usage, got ${success.changedFiles.keys}",
                usagePath.toString() in success.changedFiles,
            )
            assertEquals(
                "The source snapshot must precede K2's package mutation",
                "package move.source\n\nfun moved() = 1\n",
                success.originalTexts[sourcePath.toString()],
            )
            assertEquals(
                "The usage snapshot must precede K2's import retargeting",
                "package move.usage\n\nimport move.source.moved\n\nfun useMoved() = moved()\n",
                success.originalTexts[usagePath.toString()],
            )
            assertTrue(
                "Expected usage import rewritten to the target package, got ${success.changedFiles[usagePath.toString()]}",
                success.changedFiles[usagePath.toString()]?.contains("import move.target.moved") == true,
            )
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a package/directory mismatch is physically movable but retains its declared package
     * in the K2 result, matching the upstream `K2MoveFilesHandler.needsUpdate` contract.
     */
    fun testApply_realSession_preservesMismatchedPackage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Move File integration test")
            return
        }
        val temp = Files.createTempDirectory("nbkotlin-move-file-mismatch")
        try {
            val sourceDirectory = temp.resolve("layout/source")
            val targetDirectory = temp.resolve("layout/target")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(targetDirectory)
            val sourcePath = sourceDirectory.resolve("Mismatch.kt")
            Files.writeString(sourcePath, "package declared.somewhereElse\\n\\nfun mismatch() = 1\\n")

            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-file-mismatch-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temp),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val targetPsi = KaMoveDeclarationComputer.resolveDirectory(source.project, targetDirectory.toString())
                ?: error("Could not resolve target directory PSI")

            val outcome = KaMoveFileComputer(source).apply(
                files = listOf(source),
                targetDirectory = targetPsi,
                targetPackage = org.jetbrains.kotlin.name.FqName("layout.target"),
                updateReferences = true,
            )

            assertTrue("Expected K2 Move File success, got $outcome", outcome is KaMoveFileComputer.ApplyOutcome.Success)
            val success = outcome as KaMoveFileComputer.ApplyOutcome.Success
            assertTrue(
                "Mismatched package must be preserved, got ${success.changedFiles[sourcePath.toString()]}",
                success.changedFiles[sourcePath.toString()]?.contains("package declared.somewhereElse") == true,
            )
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies one K2 pass supports distinct target packages for directory descendants and retargets
     * imports of declarations from both source files.
     */
    fun testApply_realSession_updatesNestedDirectoryTargetsTogether() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Move Directory integration test")
            return
        }
        val temp = Files.createTempDirectory("nbkotlin-move-directory")
        try {
            val feature = temp.resolve("source/feature")
            val internal = feature.resolve("internal")
            val usageDirectory = temp.resolve("usage")
            val targetFeature = temp.resolve("target/sample/destination/feature")
            val targetInternal = targetFeature.resolve("internal")
            Files.createDirectories(internal)
            Files.createDirectories(usageDirectory)
            Files.createDirectories(targetInternal)
            val publicPath = feature.resolve("Public.kt")
            val helperPath = internal.resolve("Helper.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(publicPath, "package source.feature\n\nfun publicApi() = helper()\n")
            Files.writeString(helperPath, "package source.feature.internal\n\nfun helper() = 1\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport source.feature.publicApi\nimport source.feature.internal.helper\n\nfun use() = publicApi() + helper()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "move-directory-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temp),
            )
            val publicFile = session.getKtFileForPath(publicPath.toString()) ?: error("Could not obtain Public.kt PSI")
            val helperFile = session.getKtFileForPath(helperPath.toString()) ?: error("Could not obtain Helper.kt PSI")
            val targetFeaturePsi = KaMoveDeclarationComputer.resolveDirectory(publicFile.project, targetFeature.toString())
                ?: error("Could not resolve feature target PSI")
            val targetInternalPsi = KaMoveDeclarationComputer.resolveDirectory(publicFile.project, targetInternal.toString())
                ?: error("Could not resolve internal target PSI")

            val outcome = KaMoveFileComputer(publicFile).apply(
                mapOf(
                    publicFile to KaMoveFileComputer.MoveTarget(
                        targetFeaturePsi,
                        org.jetbrains.kotlin.name.FqName("sample.destination.feature"),
                    ),
                    helperFile to KaMoveFileComputer.MoveTarget(
                        targetInternalPsi,
                        org.jetbrains.kotlin.name.FqName("sample.destination.feature.internal"),
                    ),
                ),
                updateReferences = true,
            )

            assertTrue("Expected K2 Move Directory success, got $outcome", outcome is KaMoveFileComputer.ApplyOutcome.Success)
            val success = outcome as KaMoveFileComputer.ApplyOutcome.Success
            assertTrue(success.changedFiles[publicPath.toString()]?.contains("package sample.destination.feature") == true)
            assertTrue(success.changedFiles[helperPath.toString()]?.contains("package sample.destination.feature.internal") == true)
            assertTrue(success.changedFiles[usagePath.toString()]?.contains("import sample.destination.feature.publicApi") == true)
            assertTrue(success.changedFiles[usagePath.toString()]?.contains("import sample.destination.feature.internal.helper") == true)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    /** Locates Kotlin stdlib supplied by the Maven test runtime. */
    private fun findStdlibJar(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map { Path.of(it) }
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }
}

