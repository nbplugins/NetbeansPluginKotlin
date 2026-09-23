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
import io.github.nbplugins.kotlin.refactoring.KaChangePackageComputer
import org.jetbrains.kotlin.name.FqName
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Integration tests for the standalone K2 [KaChangePackageComputer]. */
class KaChangePackageTest : KotlinTestCase("KaChangePackageTest", "moveFile") {

    /** Verifies Change Package rewrites a file despite a package/directory mismatch without moving it. */
    fun testApply_realSession_changesMismatchedPackageWithoutMovingFile() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-mismatch")
        try {
            val sourceDirectory = temporaryRoot.resolve("layout/source")
            Files.createDirectories(sourceDirectory)
            val sourcePath = sourceDirectory.resolve("Mismatch.kt")
            Files.writeString(sourcePath, "package declared.somewhereElse\n\nfun mismatch() = 1\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-mismatch-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("target.changed")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertTrue(success.changedFiles[sourcePath.toString()]?.contains("package target.changed") == true)
            assertTrue("Change Package must not move physical files", Files.isRegularFile(sourcePath))
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /** Verifies one package change atomically retargets external Kotlin imports and code usages. */
    fun testApply_realSession_retargetsExternalImport() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-usage")
        try {
            val sourceDirectory = temporaryRoot.resolve("source")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun moved() = 1\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.source.moved\n\nfun use() = moved()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-usage-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.changed")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals("package old.source\n\nfun moved() = 1\n", success.originalTexts[sourcePath.toString()])
            assertEquals(
                "package usage\n\nimport old.source.moved\n\nfun use() = moved()\n",
                success.originalTexts[usagePath.toString()],
            )
            assertTrue(success.changedFiles[sourcePath.toString()]?.contains("package new.changed") == true)
            assertTrue(success.changedFiles[usagePath.toString()]?.contains("import new.changed.moved") == true)
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a caller in the old package receives an explicit reference after its declaration moves.
     *
     * Before Change Package's upstream whole-file usage discovery was used, this no-import call was
     * invisible to the adapter: it remained a stale simple name in the old package.
     */
    fun testApply_realSession_retargetsSamePackageReferenceWithoutImport() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-same-package")
        try {
            val oldPackageDirectory = temporaryRoot.resolve("old/source")
            Files.createDirectories(oldPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            val callerPath = oldPackageDirectory.resolve("Caller.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun moved() = 1\n")
            Files.writeString(callerPath, "package old.source\n\nfun use() = moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-same-package-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.changed")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertTrue(success.changedFiles[sourcePath.toString()]?.contains("package new.changed") == true)
            assertTrue(
                "A same-package caller must import the declaration's new package, got ${success.changedFiles[callerPath.toString()]}",
                success.changedFiles[callerPath.toString()]?.contains("import new.changed.moved") == true,
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /** Verifies recursive-style per-file packages retarget imports for every selected source together. */
    fun testApply_realSession_supportsDistinctDescendantPackages() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-directory")
        try {
            val folder = temporaryRoot.resolve("physical/feature")
            val internal = folder.resolve("internal")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(internal)
            Files.createDirectories(usageDirectory)
            val publicPath = folder.resolve("Public.kt")
            val helperPath = internal.resolve("Helper.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(publicPath, "package old.feature\n\nfun publicApi() = helper()\n")
            Files.writeString(helperPath, "package old.feature.internal\n\nfun helper() = 1\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.feature.publicApi\nimport old.feature.internal.helper\n\nfun use() = publicApi() + helper()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-directory-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val publicFile = session.getKtFileForPath(publicPath.toString()) ?: error("Could not obtain Public.kt PSI")
            val helperFile = session.getKtFileForPath(helperPath.toString()) ?: error("Could not obtain Helper.kt PSI")

            val outcome = KaChangePackageComputer(publicFile).apply(
                mapOf(publicFile to FqName("new.target"), helperFile to FqName("new.target.internal")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertTrue(success.changedFiles[publicPath.toString()]?.contains("package new.target") == true)
            assertTrue(success.changedFiles[helperPath.toString()]?.contains("package new.target.internal") == true)
            assertTrue(success.changedFiles[usagePath.toString()]?.contains("import new.target.publicApi") == true)
            assertTrue(success.changedFiles[usagePath.toString()]?.contains("import new.target.internal.helper") == true)
            assertTrue("Change Package must retain the first physical path", Files.isRegularFile(publicPath))
            assertTrue("Change Package must retain the nested physical path", Files.isRegularFile(helperPath))
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /** Locates Kotlin stdlib supplied by the Maven test runtime. */
    private fun findStdlibJar(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }
}
