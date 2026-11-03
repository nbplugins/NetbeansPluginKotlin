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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

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
            assertEquals(
                "A same-package caller without existing imports needs a separated import section",
                "package old.source\n\nimport new.changed.moved\n\nfun use() = moved()\n",
                success.changedFiles[callerPath.toString()],
            )
            assertEquals(
                "Every generated import change needs an exact pre-refactoring snapshot for Undo Last Refactoring",
                "package old.source\n\nfun use() = moved()\n",
                success.originalTexts[callerPath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies Change Package retains a rebound qualified call when standalone FIR cannot shorten a
     * range that also contains a local declaration.
     */
    fun testApply_realSession_keepsQualifiedReferenceWhenLocalDeclarationPreventsShortening() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-local-shortener")
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
                "package usage\n\nimport old.source.moved\n\nfun use(): Int {\n" +
                    "    fun local() = moved()\n" +
                    "    return local()\n" +
                    "}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-local-shortener-integration",
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
            val usageText = success.changedFiles[usagePath.toString()] ?: error("Usage.kt was not changed")
            assertTrue("Retargeted local call must retain a valid reference", usageText.contains("new.changed.moved()") || usageText.contains("moved()"))
            assertFalse("Internal root-resolution prefix must never reach source text", usageText.contains("<root>"))
            assertTrue("Change Package must not move the declaration file", Files.isRegularFile(sourcePath))
            assertTrue("Change Package must not move the usage file", Files.isRegularFile(usagePath))
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

    /** Verifies a directory rename's path-derived package rewrites every moved descendant together. */
    fun testApply_realSession_appliesOnePathDerivedPackageToAllDescendants() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-rename-package-directory")
        try {
            val folder = temporaryRoot.resolve("physical/before")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(folder)
            Files.createDirectories(usageDirectory)
            val firstPath = folder.resolve("First.kt")
            val secondPath = folder.resolve("Second.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(firstPath, "package declared.pkg\n\nfun first() = 1\n")
            Files.writeString(secondPath, "package declared.pkg\n\nfun second() = first()\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport declared.pkg.first\n\nfun use() = first()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-package-directory-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val firstFile = session.getKtFileForPath(firstPath.toString()) ?: error("Could not obtain First.kt PSI")
            val secondFile = session.getKtFileForPath(secondPath.toString()) ?: error("Could not obtain Second.kt PSI")

            val outcome = KaChangePackageComputer(firstFile).apply(
                mapOf(firstFile to FqName("renamed"), secondFile to FqName("renamed")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            val firstText = success.changedFiles[firstPath.toString()].orEmpty()
            val secondText = success.changedFiles[secondPath.toString()].orEmpty()
            assertTrue(firstText, firstText.contains("package renamed"))
            assertTrue(secondText, secondText.contains("package renamed"))
            assertFalse(
                "a co-moved same-package caller must not gain an import: $secondText",
                secondText.contains("import renamed.first"),
            )
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertTrue(
                "External import must follow the declaration; changed=${success.changedFiles.keys}, usage=$usageText",
                usageText.contains("import renamed.first"),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a moved file explicitly imports declarations that remain in its former package.
     *
     * Same-package visibility disappears with the package directive and leaves no import directive
     * to rewrite, so without a captured import the reference would silently become unresolved.
     */
    fun testApply_realSession_addsImportForOutgoingSamePackageReference() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-outgoing")
        try {
            val oldPackageDirectory = temporaryRoot.resolve("old/source")
            Files.createDirectories(oldPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            val staysPath = oldPackageDirectory.resolve("Stays.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun moved() = stays()\n")
            Files.writeString(staysPath, "package old.source\n\nfun stays() = 1\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-outgoing-integration",
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
            assertEquals(
                "A moved file must import declarations that stay in its former package",
                "package new.changed\n\nimport old.source.stays\n\nfun moved() = stays()\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies fully qualified references inside `${...}` templates follow a moved declaration.
     *
     * A template expression is executable Kotlin code, not the string-text occurrence excluded from
     * the refactoring, so string tracking must re-enter code mode inside the braces.
     */
    fun testApply_realSession_rewritesQualifiedReferenceInsideStringTemplate() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-template")
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
                "package usage\n\nimport old.source.moved\n\nfun use() = \"\${old.source.moved()}\"\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-template-integration",
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
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertTrue("A template expression must be retargeted: $usageText", usageText.contains("\${new.changed.moved()}"))
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies raw strings with interior quotes keep their literal text after reference retargeting.
     *
     * Treating the first interior quote as the end of the raw string made later literal text look
     * like code, silently changing runtime string values.
     */
    fun testApply_realSession_keepsRawStringLiteralWithInteriorQuotes() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-raw-string")
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
                "package usage\n\nimport old.source.moved\n\nfun use() = moved()\n\n" +
                    "val text = \"\"\"say \"old.source.moved\" now\"\"\"\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-raw-string-integration",
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
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertTrue("The import must still follow the declaration: $usageText", usageText.contains("import new.changed.moved"))
            assertTrue(
                "Raw string content must stay verbatim: $usageText",
                usageText.contains("say \"old.source.moved\" now"),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies moving a file out of the root package never rewrites its own declaration names.
     *
     * Root-package declarations have single-segment FQNs, so token replacement of the old name
     * turned `fun moved()` into `fun dest.moved()`. Bindings survive through imports instead.
     */
    fun testApply_realSession_keepsDeclarationNamesWhenMovingFromDefaultPackage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-default-package")
        try {
            val sourcePath = temporaryRoot.resolve("Moved.kt")
            Files.writeString(sourcePath, "fun moved() = 1\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-default-package-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            val changedText = success.changedFiles[sourcePath.toString()].orEmpty()
            assertTrue("The new package directive is required: $changedText", changedText.contains("package dest"))
            assertTrue("Declaration names must survive the package change: $changedText", changedText.contains("fun moved() = 1"))
            assertFalse("A declaration must never be rewritten to a qualified name: $changedText", changedText.contains("dest.moved"))
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a caller importing the whole old package receives an explicit import for the move.
     *
     * The wildcard import still covers declarations that stay behind, so it must be preserved
     * while the moved declaration gets its own explicit import.
     */
    fun testApply_realSession_addsExplicitImportForWildcardImportCaller() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-wildcard")
        try {
            val sourceDirectory = temporaryRoot.resolve("source")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun moved() = 1\n")
            Files.writeString(sourceDirectory.resolve("Stayed.kt"), "package old.source\n\nfun stayed() = 0\n")
            Files.writeString(usagePath, "package usage\n\nimport old.source.*\n\nfun use() = moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-wildcard-integration",
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
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertTrue(
                "A wildcard importer needs an explicit import for the moved declaration: $usageText",
                usageText.contains("import new.changed.moved"),
            )
            assertTrue(
                "The wildcard import must stay for declarations that remain: $usageText",
                usageText.contains("import old.source.*"),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a package-qualified call from a wildcard importer needs no explicit import.
     *
     * `a.moved()` is rewritten in place to `b.moved()`, so requesting `import b.moved` would
     * collide with the caller's unrelated `import c.moved`.
     */
    fun testApply_realSession_skipsIncomingImportForPackageQualifiedWildcardUsage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-wildcard-qualified")
        try {
            val sourceDirectory = temporaryRoot.resolve("a")
            val usageDirectory = temporaryRoot.resolve("call")
            val otherDirectory = temporaryRoot.resolve("c")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            Files.createDirectories(otherDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            val otherPath = otherDirectory.resolve("Other.kt")
            Files.writeString(sourcePath, "package a\n\nfun moved() = 1\n")
            Files.writeString(sourceDirectory.resolve("Stayed.kt"), "package a\n\nfun stayed() = 0\n")
            Files.writeString(usagePath, "package call\n\nimport a.*\nimport c.moved\n\nfun use() = a.moved() + moved()\n")
            Files.writeString(otherPath, "package c\n\nfun moved() = 2\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-wildcard-qualified-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("b")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A qualified usage must not add a conflicting incoming import",
                "package call\n\nimport a.*\nimport c.moved\n\nfun use() = b.moved() + moved()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an alias-bound usage of a moved declaration needs no incoming unaliased import.
     *
     * The rewritten `import b.moved as alias` keeps `alias()` bound, so adding `import b.moved`
     * would only make the caller's unrelated `moved()` call ambiguous.
     */
    fun testApply_realSession_skipsIncomingImportForAliasBoundWildcardUsage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-wildcard-alias")
        try {
            val sourceDirectory = temporaryRoot.resolve("a")
            val usageDirectory = temporaryRoot.resolve("call")
            val otherDirectory = temporaryRoot.resolve("c")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            Files.createDirectories(otherDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            val otherPath = otherDirectory.resolve("Other.kt")
            Files.writeString(sourcePath, "package a\n\nfun moved() = 1\n")
            Files.writeString(sourceDirectory.resolve("Stayed.kt"), "package a\n\nfun stayed() = 0\n")
            Files.writeString(
                usagePath,
                "package call\n\nimport a.*\nimport a.moved as alias\nimport c.moved\n\nfun use() = alias() + moved()\n",
            )
            Files.writeString(otherPath, "package c\n\nfun moved() = 2\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-wildcard-alias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("b")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An alias-bound usage must not add a conflicting incoming import",
                "package call\n\nimport a.*\nimport b.moved as alias\nimport c.moved\n\nfun use() = alias() + moved()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an explicit import of a default-package declaration follows it into a named package.
     *
     * Bare names are excluded from reference replacement to protect declaration names, but their
     * import directives still need the new package prefix.
     */
    fun testApply_realSession_rewritesExplicitImportOfDefaultPackageDeclaration() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-default-import")
        try {
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(usageDirectory)
            val sourcePath = temporaryRoot.resolve("Foo.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(sourcePath, "class Foo\n")
            Files.writeString(usagePath, "package usage\n\nimport Foo\n\nfun use() = Foo()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-default-import-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertTrue(
                "An explicit default-package import must gain the new package: $usageText",
                usageText.contains("import dest.Foo"),
            )
            assertTrue("The resolved call itself needs no rewrite: $usageText", usageText.contains("fun use() = Foo()"))
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies only references bound to the moved declaration are rewritten.
     *
     * A receiver chain that merely spells the same text but resolves to unrelated members must keep
     * its original text; identifier boundaries cannot tell the two apart.
     */
    fun testApply_realSession_keepsUnboundReceiverChainWhenSpellingMatchesMovedFqName() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-unbound-chain")
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
                "package usage\n\nimport old.source.moved\n\n" +
                    "class Holder {\n    val source: Holder get() = this\n    val moved: Int get() = 1\n}\n\n" +
                    "fun read(old: Holder) = old.source.moved\n\nfun use() = moved()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-unbound-chain-integration",
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
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertTrue("The import itself must follow the declaration: $usageText", usageText.contains("import new.changed.moved"))
            assertTrue(
                "A receiver chain bound to unrelated members must stay untouched: $usageText",
                usageText.contains("fun read(old: Holder) = old.source.moved"),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies references to a moved type alias are retargeted even though the processor ignores it.
     *
     * Change Package usage discovery only reports functions, properties, and classes, so aliases
     * need the declaration-level scan: a same-package caller must gain an explicit import, and a
     * qualified type reference must follow the declaration.
     */
    fun testApply_realSession_retargetsTypeAliasReferences() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-typealias")
        try {
            val sourceDirectory = temporaryRoot.resolve("source")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val aliasPath = sourceDirectory.resolve("Alias.kt")
            val siblingPath = sourceDirectory.resolve("Sibling.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(aliasPath, "package old.source\n\ntypealias Handler = () -> Unit\n")
            Files.writeString(siblingPath, "package old.source\n\nval sibling: Handler = {}\n")
            Files.writeString(usagePath, "package usage\n\nval usageAlias: old.source.Handler = {}\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-typealias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(aliasPath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.changed")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            val siblingText = success.changedFiles[siblingPath.toString()].orEmpty()
            assertTrue(
                "A same-package caller must gain an explicit alias import: $siblingText",
                siblingText.contains("import new.changed.Handler"),
            )
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertTrue(
                "A qualified alias reference must follow the declaration: $usageText",
                usageText.contains("val usageAlias: new.changed.Handler = {}"),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a root-package caller receives an import even though it has no package directive.
     *
     * The import must be inserted before the first declaration while keeping header comments.
     */
    fun testApply_realSession_addsImportToCallerWithoutPackageDirective() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-root-caller")
        try {
            val sourcePath = temporaryRoot.resolve("Moved.kt")
            val callerPath = temporaryRoot.resolve("Caller.kt")
            Files.writeString(sourcePath, "fun moved() = 1\n")
            Files.writeString(callerPath, "// Generated helper\n\nfun use() = moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-root-caller-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            val callerText = success.changedFiles[callerPath.toString()].orEmpty()
            assertEquals(
                "A root-package caller must gain an import after its header comment",
                "// Generated helper\n\nimport dest.moved\n\nfun use() = moved()\n",
                callerText,
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies import rewriting uses PSI ranges rather than the rendered import path text.
     *
     * A backticked segment makes `importPath.pathStr` differ from the source spelling; locating
     * that rendered path in the directive returned -1 and corrupted text before the directive.
     */
    fun testApply_realSession_rewritesBacktickedImportPath() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-backtick-import")
        try {
            val sourceDirectory = temporaryRoot.resolve("source")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun moved() = 1\n")
            Files.writeString(usagePath, "package usage\n\nimport old.source.`moved`\n\nfun use() = moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-backtick-import-integration",
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
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertTrue(
                "A backticked import path must be rewritten without corrupting the header: $usageText",
                usageText.startsWith("package usage\n\nimport new.changed.moved\n"),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an import-looking line inside a raw string never becomes the insertion boundary.
     *
     * Text scanning cannot distinguish executable header imports from literals; the import must be
     * inserted after the package directive so the runtime string value stays intact.
     */
    fun testApply_realSession_insertsImportAtPackageBoundaryDespiteImportLineInRawString() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-import-in-string")
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
                "package old.source\n\nfun use() = moved()\n\nval sample = \"\"\"\nimport unrelated.foo\n\"\"\"\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-import-in-string-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertEquals(
                "The import must go after the package directive, not inside the literal",
                "package old.source\n\nimport dest.moved\n\nfun use() = moved()\n\n"
                    + "val sample = \"\"\"\nimport unrelated.foo\n\"\"\"\n",
                usageText,
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a literal repeating the future import path does not suppress a required import.
     *
     * Only real import directives count as already present; otherwise the moved declaration stays
     * unresolved while the literal keeps its original runtime value.
     */
    fun testApply_realSession_insertsImportWhenRawStringRepeatsNewImportPath() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-import-duplicate-in-string")
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
                "package old.source\n\nfun use() = moved()\n\nval sample = \"\"\"\nimport new.changed.moved\n\"\"\"\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-import-duplicate-in-string-integration",
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
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertEquals(
                "A literal must not count as an existing import",
                "package old.source\n\nimport new.changed.moved\n\nfun use() = moved()\n\n"
                    + "val sample = \"\"\"\nimport new.changed.moved\n\"\"\"\n",
                usageText,
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a backticked qualified reference follows the moved declaration.
     *
     * K2 resolves such a chain even though its source spelling differs from the canonical FQN, so
     * the comparison must work on identifier segments while replacement keeps the PSI range.
     */
    fun testApply_realSession_retargetsBacktickedQualifiedReference() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-backtick-reference")
        try {
            val sourceDirectory = temporaryRoot.resolve("source")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun moved() = 1\n")
            Files.writeString(usagePath, "package usage\n\nfun use() = old.source.`moved`()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-backtick-reference-integration",
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
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertEquals(
                "A backticked qualified reference must follow the declaration",
                "package usage\n\nfun use() = new.changed.moved()\n",
                usageText,
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a qualified reference split across lines follows the moved declaration.
     *
     * Whitespace around the dot changes the chain's source spelling but not the identifiers K2
     * resolved, and replacement must span the whole chain.
     */
    fun testApply_realSession_retargetsQualifiedReferenceWithWhitespaceAroundDot() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-spaced-reference")
        try {
            val sourceDirectory = temporaryRoot.resolve("source")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun moved() = 1\n")
            Files.writeString(usagePath, "package usage\n\nfun use() = old.source\n    .moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-spaced-reference-integration",
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
            val usageText = success.changedFiles[usagePath.toString()].orEmpty()
            assertEquals(
                "A multi-line qualified reference must follow the declaration",
                "package usage\n\nfun use() = new.changed.moved()\n",
                usageText,
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a reference before the package directive is not shifted by the directive's new length.
     *
     * A file annotation precedes the package directive in source order but is captured in the same
     * coordinate space; applying the delta to it moved the replacement into unrelated text.
     */
    fun testApply_realSession_keepsFileAnnotationReferenceBeforePackageDirectiveAligned() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-file-annotation")
        try {
            val sourcePath = temporaryRoot.resolve("Moved.kt")
            val originalText = "@file:Suppress(old.pkg.MESSAGE)\npackage old.pkg\n\nconst val MESSAGE = \"unused\"\n"
            Files.writeString(sourcePath, originalText)
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-file-annotation-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.much.longer.destination")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A reference before the package directive must keep its own offsets",
                "@file:Suppress(new.much.longer.destination.MESSAGE)\n" +
                    "package new.much.longer.destination\n\nconst val MESSAGE = \"unused\"\n",
                success.changedFiles[sourcePath.toString()].orEmpty(),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies generated references and imports escape declaration names that need backticks.
     *
     * A declaration named `two words` has no plain Kotlin spelling, so `FqName.asString()` would
     * emit invalid source in the rewritten qualified reference and in the compensating import.
     */
    fun testApply_realSession_escapesBacktickedDeclarationInGeneratedText() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-backtick-name")
        try {
            val sourceDirectory = temporaryRoot.resolve("source")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val siblingPath = sourceDirectory.resolve("Sibling.kt")
            val usagePath = usageDirectory.resolve("Usage.kt")
            Files.writeString(sourcePath, "package old.pkg\n\nfun `two words`() = 1\n")
            Files.writeString(siblingPath, "package old.pkg\n\nfun sibling() = `two words`()\n")
            Files.writeString(usagePath, "package usage\n\nfun use() = old.pkg.`two words`()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-backtick-name-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A generated qualified reference must escape the declaration name",
                "package usage\n\nfun use() = dest.`two words`()\n",
                success.changedFiles[usagePath.toString()].orEmpty(),
            )
            assertEquals(
                "A generated import must escape the declaration name",
                "package old.pkg\n\nimport dest.`two words`\n\nfun sibling() = `two words`()\n",
                success.changedFiles[siblingPath.toString()].orEmpty(),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a new package directive is separated from the declaration when a header comment leads.
     *
     * The empty package directive of a root-package file sits at the first declaration token, after
     * the comment, so the in-place replacement must still restore the line break.
     */
    fun testApply_realSession_separatesPackageDirectiveAfterHeaderComment() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-header-comment")
        try {
            val sourcePath = temporaryRoot.resolve("Moved.kt")
            Files.writeString(sourcePath, "// Generated file\nfun moved() = 1\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-header-comment-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The new directive must not be glued to the declaration",
                "package dest\n\n// Generated file\nfun moved() = 1\n",
                success.changedFiles[sourcePath.toString()].orEmpty(),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a file annotation stays before a newly written package directive.
     *
     * Kotlin requires file annotations to precede the package header, so a root-package file that
     * gains a package must not move the directive above its annotation.
     */
    fun testApply_realSession_keepsFileAnnotationBeforeNewPackageDirective() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-new-directive-annotation")
        try {
            val sourcePath = temporaryRoot.resolve("Moved.kt")
            Files.writeString(sourcePath, "@file:JvmName(\"Moved\")\nfun moved() = 1\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-new-directive-annotation-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A file annotation must stay above the package directive",
                "@file:JvmName(\"Moved\")\npackage dest\nfun moved() = 1\n",
                success.changedFiles[sourcePath.toString()].orEmpty(),
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a moved file imports a Java class that stays in its former package.
     *
     * Java classes have no Kotlin PSI declaration, so the outgoing-dependency pass must recognise
     * them through their class id; otherwise the simple name loses its binding after the move.
     */
    fun testApply_realSession_addsImportForOutgoingSamePackageJavaClass() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-outgoing-java")
        val buildRoot = Files.createTempDirectory("nbkotlin-change-package-outgoing-java-build")
        try {
            val javaJar = compileJavaClassJar(buildRoot, "old.source", "JavaHelper") ?: run {
                println("Java compiler unavailable — skipping outgoing Java class test")
                return
            }
            val oldPackageDirectory = temporaryRoot.resolve("old/source")
            Files.createDirectories(oldPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun create() = JavaHelper()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-outgoing-java-integration",
                binaryJars = listOf(stdlib, javaJar),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.changed")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A moved file must import a Java class that stays in its former package",
                "package new.changed\n\nimport old.source.JavaHelper\n\nfun create() = JavaHelper()\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
            buildRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a moved file imports a Kotlin class whose constructor it calls.
     *
     * A constructor call resolves to a constructor symbol rather than to the class declaration,
     * so outgoing dependency capture must fall back to the constructed class id.
     */
    fun testApply_realSession_addsImportForOutgoingSamePackageClassConstructor() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-outgoing-constructor")
        try {
            val oldPackageDirectory = temporaryRoot.resolve("old/source")
            Files.createDirectories(oldPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            val staysPath = oldPackageDirectory.resolve("Stays.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun create() = Helper()\n")
            Files.writeString(staysPath, "package old.source\n\nclass Helper\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-outgoing-constructor-integration",
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
            assertEquals(
                "A moved file must import a class that stays in its former package",
                "package new.changed\n\nimport old.source.Helper\n\nfun create() = Helper()\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a moved file imports a Kotlin class that declares an explicit primary constructor.
     *
     * The constructor symbol's PSI is a [org.jetbrains.kotlin.psi.KtPrimaryConstructor], which is a
     * named declaration whose parent is the class rather than the file. Outgoing dependency capture
     * must therefore consult the constructed class id before the top-level PSI check, otherwise the
     * call loses its import and stops resolving after the move.
     */
    fun testApply_realSession_addsImportForOutgoingSamePackageExplicitConstructor() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-outgoing-explicit-constructor")
        try {
            val oldPackageDirectory = temporaryRoot.resolve("old/source")
            Files.createDirectories(oldPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            val staysPath = oldPackageDirectory.resolve("Stays.kt")
            Files.writeString(sourcePath, "package old.source\n\nfun create() = Helper(1)\n")
            Files.writeString(staysPath, "package old.source\n\nclass Helper(val x: Int)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-outgoing-explicit-constructor-integration",
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
            assertEquals(
                "A moved file must import a class whose explicit constructor it calls",
                "package new.changed\n\nimport old.source.Helper\n\nfun create() = Helper(1)\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a fully qualified call to the former package needs no import.
     *
     * `old.source.Helper(1)` spells out the package itself, so adding `import old.source.Helper`
     * would only collide with the caller's existing `import other.Helper`.
     */
    fun testApply_realSession_skipsImportForPackageQualifiedConstructorCall() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-qualified-constructor")
        try {
            val oldPackageDirectory = temporaryRoot.resolve("old/source")
            val otherPackageDirectory = temporaryRoot.resolve("other")
            Files.createDirectories(oldPackageDirectory)
            Files.createDirectories(otherPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            val staysPath = oldPackageDirectory.resolve("Stays.kt")
            val otherPath = otherPackageDirectory.resolve("Helper.kt")
            Files.writeString(
                sourcePath,
                "package old.source\n\nimport other.Helper\n\nfun create() = old.source.Helper(1).x + Helper(2).x\n",
            )
            Files.writeString(staysPath, "package old.source\n\nclass Helper(val x: Int)\n")
            Files.writeString(otherPath, "package other\n\nclass Helper(val x: Int)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-qualified-constructor-integration",
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
            assertEquals(
                "A package-qualified call must not add a conflicting import",
                "package new.changed\n\nimport other.Helper\n\nfun create() = old.source.Helper(1).x + Helper(2).x\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a reference bound through an import alias needs no unaliased import.
     *
     * The moved file binds `a.Helper` as `OriginalHelper`, so using that name keeps the dependency;
     * adding `import a.Helper` would collide with the existing `import c.Helper` and break the
     * unqualified `Helper` usage.
     */
    fun testApply_realSession_keepsAliasedOutgoingImportWithoutAddingUnaliasedOne() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-outgoing-alias")
        try {
            val oldPackageDirectory = temporaryRoot.resolve("old/source")
            val otherPackageDirectory = temporaryRoot.resolve("other")
            Files.createDirectories(oldPackageDirectory)
            Files.createDirectories(otherPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            val staysPath = oldPackageDirectory.resolve("Stays.kt")
            val otherPath = otherPackageDirectory.resolve("Helper.kt")
            Files.writeString(
                sourcePath,
                "package old.source\n\n" +
                    "import old.source.Helper as OriginalHelper\n" +
                    "import other.Helper\n\n" +
                    "fun create() = OriginalHelper(1).x + Helper(2).x\n",
            )
            Files.writeString(staysPath, "package old.source\n\nclass Helper(val x: Int)\n")
            Files.writeString(otherPath, "package other\n\nclass Helper(val x: Int)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-outgoing-alias-integration",
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
            assertEquals(
                "An alias-bound reference must not add an unaliased import",
                "package new.changed\n\n" +
                    "import old.source.Helper as OriginalHelper\n" +
                    "import other.Helper\n\n" +
                    "fun create() = OriginalHelper(1).x + Helper(2).x\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a package-qualified type reference needs no import.
     *
     * `a.Helper` as a type names the package itself, so the moved file must keep its existing
     * `import c.Helper` usable without an extra unaliased `import a.Helper`.
     */
    fun testApply_realSession_skipsImportForPackageQualifiedType() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-qualified-type")
        try {
            val oldPackageDirectory = temporaryRoot.resolve("a")
            val otherPackageDirectory = temporaryRoot.resolve("c")
            Files.createDirectories(oldPackageDirectory)
            Files.createDirectories(otherPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            val staysPath = oldPackageDirectory.resolve("Stays.kt")
            val otherPath = otherPackageDirectory.resolve("Helper.kt")
            Files.writeString(
                sourcePath,
                "package a\n\nimport c.Helper\n\nfun create(): a.Helper? = null\n\nfun other(): Helper = Helper(1)\n",
            )
            Files.writeString(staysPath, "package a\n\nclass Helper(val x: Int)\n")
            Files.writeString(otherPath, "package c\n\nclass Helper(val x: Int)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-qualified-type-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("b")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A package-qualified type must not add a conflicting import",
                "package b\n\nimport c.Helper\n\nfun create(): a.Helper? = null\n\nfun other(): Helper = Helper(1)\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a file leaving the default package imports the declarations that stay behind.
     *
     * Root-package declarations are visible only to files in the root package, so moving the file
     * into a named package must add a bare `import stays`.
     */
    fun testApply_realSession_addsRootPackageImportWhenLeavingDefaultPackage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-root-outgoing")
        try {
            val sourcePath = temporaryRoot.resolve("Moved.kt")
            val staysPath = temporaryRoot.resolve("Stays.kt")
            Files.writeString(sourcePath, "fun moved() = stays()\n")
            Files.writeString(staysPath, "fun stays() = 1\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-root-outgoing-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A file leaving the default package must import the declarations that stay in it",
                "package dest\n\nimport stays\n\nfun moved() = stays()\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an import path containing a comment still follows its moved declaration.
     *
     * Segment matching must come from PSI identifiers; splitting source text leaves comment tokens
     * inside a segment and the directive is then never rewritten.
     */
    fun testApply_realSession_retargetsImportWithInterspersedComment() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-import-comment")
        try {
            val sourceDirectory = temporaryRoot.resolve("old/pkg")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package old.pkg\n\nclass Moved\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old./* note */pkg.Moved\n\nfun use(): Moved? = null\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-import-comment-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.pkg")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An import path with an interspersed comment must still be retargeted",
                "package usage\n\nimport new.pkg.Moved\n\nfun use(): Moved? = null\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a qualified call containing a comment is still rewritten in place.
     *
     * `old./* note */pkg.moved()` spells the old FQN in PSI terms even though its text contains a
     * comment token; the chain must be recognized and rewritten without that token.
     */
    fun testApply_realSession_retargetsQualifiedCallWithInterspersedComment() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-call-comment")
        try {
            val sourceDirectory = temporaryRoot.resolve("old/pkg")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package old.pkg\n\nfun moved() = 1\n")
            Files.writeString(usagePath, "package usage\n\nfun use() = old./* note */pkg.moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-call-comment-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.pkg")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A qualified call with an interspersed comment must be rewritten in place",
                "package usage\n\nfun use() = new.pkg.moved()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an extension receiver spelled like the old package is not treated as a qualifier.
     *
     * `a.decorate()` calls the extension on a local `a: String`; matching the chain text against
     * the old FQN `a.decorate` would replace the receiver, so the receiver must be known to
     * resolve to a package before the whole chain is rewritten.
     */
    fun testApply_realSession_keepsExtensionReceiverSpelledLikePackage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-extension-receiver")
        try {
            val sourceDirectory = temporaryRoot.resolve("old")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Extension.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package a\n\nfun String.decorate() = this\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport a.decorate\n\nfun use(a: String) = a.decorate()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-extension-receiver-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("b")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The extension receiver must survive while the import follows the move",
                "package usage\n\nimport b.decorate\n\nfun use(a: String) = a.decorate()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a call receiver that happens to spell the old package is left alone.
     *
     * `a.b()` in `a.b().decorate()` is a call on a local value whose identifiers match the moved
     * package path by coincidence. The receiver call is unresolved, so the chain root resolves to
     * no symbol and cannot prove the chain is an expression; replacing the chain with the new FQN
     * would delete the receiver and leave a dangling call, so only chains made purely of names may
     * be rewritten.
     */
    fun testApply_realSession_keepsCallReceiverBeforeQualifiedName() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-call-receiver")
        try {
            val sourceDirectory = temporaryRoot.resolve("old")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Extension.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package a\n\nfun String.decorate() = this\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport a.decorate\n\nfun use() = a().decorate()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-call-receiver-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("x")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A call receiver must not be replaced by the package FQN",
                "package usage\n\nimport x.decorate\n\nfun use() = a().decorate()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a moved function does not capture imports of an unrelated subpackage.
     *
     * Kotlin allows a package and a top-level function to share a name (`p.tools` and `p.tools()`).
     * Moving only the function must not rewrite a caller's `import p.tools.utility`, which belongs
     * to the unrelated package that stays put; suffix-preserving rewrites are valid for moved
     * classifiers only, because their nested declarations really do follow them.
     */
    fun testApply_realSession_keepsSubpackageImportMatchingMovedFunctionPrefix() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-callable-prefix")
        try {
            val sourceDirectory = temporaryRoot.resolve("source")
            val utilityDirectory = temporaryRoot.resolve("utility")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(utilityDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Tools.kt")
            val utilityPath = utilityDirectory.resolve("Utility.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package p\n\nfun tools() = 1\n")
            Files.writeString(utilityPath, "package p.tools\n\nfun utility() = 2\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport p.tools.utility\n\nval value = utility()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-callable-prefix-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertTrue(success.changedFiles[sourcePath.toString()]?.contains("package dest") == true)
            assertEquals(
                "An import of the unrelated p.tools package must stay untouched",
                "package usage\n\nimport p.tools.utility\n\nval value = utility()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an explicit constructor call is retargeted with its moved class.
     *
     * A constructor call resolves to a constructor symbol whose PSI is the class's primary
     * constructor, so usage discovery must compare the constructed class id; both the unqualified
     * call in a former package mate and the qualified call elsewhere must follow the move.
     */
    fun testApply_realSession_addsIncomingImportForExplicitConstructorCall() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-incoming-constructor")
        try {
            val sourceDirectory = temporaryRoot.resolve("a")
            val qualifiedDirectory = temporaryRoot.resolve("c")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(qualifiedDirectory)
            val sourcePath = sourceDirectory.resolve("Helper.kt")
            val matePath = sourceDirectory.resolve("Mate.kt")
            val qualifiedPath = qualifiedDirectory.resolve("Other.kt")
            Files.writeString(sourcePath, "package a\n\nclass Helper(val x: Int)\n")
            Files.writeString(matePath, "package a\n\nfun use() = Helper(1)\n")
            Files.writeString(qualifiedPath, "package c\n\nfun use() = a.Helper(1)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-incoming-constructor-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("b")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A former package mate must import the moved class",
                "package a\n\nimport b.Helper\n\nfun use() = Helper(1)\n",
                success.changedFiles[matePath.toString()],
            )
            assertEquals(
                "A package-qualified constructor call must follow the moved class",
                "package c\n\nfun use() = b.Helper(1)\n",
                success.changedFiles[qualifiedPath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a wildcard import rooted in a moved class follows that class.
     *
     * `import old.pkg.Outer.*` binds nested declarations through `Outer`; once `Outer` moves the
     * prefix must move with it, or every nested reference in the caller stops resolving.
     */
    fun testApply_realSession_retargetsWildcardImportOfMovedClass() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-class-wildcard")
        try {
            val sourceDirectory = temporaryRoot.resolve("old/pkg")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Outer.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package old.pkg\n\nclass Outer {\n    class Nested\n}\n")
            Files.writeString(usagePath, "package usage\n\nimport old.pkg.Outer.*\n\nfun use(): Nested? = null\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-class-wildcard-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.pkg")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A wildcard import of a moved class must follow the class",
                "package usage\n\nimport new.pkg.Outer.*\n\nfun use(): Nested? = null\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a wildcard rooted in a class nested inside a moved class follows that move.
     *
     * `import old.pkg.Outer.Inner.*` names `Outer` only as a prefix, so the caller is not found by
     * usage search; the wildcard prefix must be retargeted with its nested suffix and `.*` kept.
     */
    fun testApply_realSession_retargetsWildcardImportRootedInNestedClass() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-nested-wildcard")
        try {
            val sourceDirectory = temporaryRoot.resolve("old/pkg")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Outer.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(
                sourcePath,
                "package old.pkg\n\nclass Outer {\n    class Inner {\n        class Nested\n    }\n}\n",
            )
            Files.writeString(usagePath, "package usage\n\nimport old.pkg.Outer.Inner.*\n\nfun use(): Nested? = null\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-nested-wildcard-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.pkg")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A wildcard import rooted in a nested class must keep its suffix and follow the move",
                "package usage\n\nimport new.pkg.Outer.Inner.*\n\nfun use(): Nested? = null\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a nested wildcard suffix keeps the backticks its class name requires.
     *
     * The suffix is rebuilt from the resolved FQN, so a keyword-named nested class must be escaped
     * again or the generated import stops being valid Kotlin.
     */
    fun testApply_realSession_escapesKeywordNamedNestedWildcard() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-escaped-wildcard")
        try {
            val sourceDirectory = temporaryRoot.resolve("old/pkg")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Outer.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(
                sourcePath,
                "package old.pkg\n\nclass Outer {\n    class `when` {\n        class Nested\n    }\n}\n",
            )
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.pkg.Outer.`when`.*\n\nfun use(): Nested? = null\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-escaped-wildcard-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.pkg")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A keyword-named nested wildcard must keep its backticks",
                "package usage\n\nimport new.pkg.Outer.`when`.*\n\nfun use(): Nested? = null\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a caller importing a nested declaration directly follows the enclosing class move.
     *
     * `import old.pkg.Outer.Nested` names the moved class only as a path prefix, so usage search
     * cannot discover the caller through `Nested` references; the import itself must be enough.
     */
    fun testApply_realSession_retargetsDirectNestedImport() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-nested-import")
        try {
            val sourceDirectory = temporaryRoot.resolve("old/pkg")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Outer.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package old.pkg\n\nclass Outer {\n    class Nested\n}\n")
            Files.writeString(usagePath, "package usage\n\nimport old.pkg.Outer.Nested\n\nfun use(): Nested? = null\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-nested-import-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.pkg")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A direct import of a nested declaration must follow the moved class",
                "package usage\n\nimport new.pkg.Outer.Nested\n\nfun use(): Nested? = null\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a package wildcard stays when the caller still uses declarations left behind.
     *
     * Rewriting `import old.pkg.*` wholesale would drop bindings for declarations that do not
     * move; the caller instead gains an explicit import for the moved declaration.
     */
    fun testApply_realSession_keepsPackageWildcardForDeclarationsStayingBehind() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-package-wildcard")
        try {
            val sourceDirectory = temporaryRoot.resolve("old/pkg")
            val usageDirectory = temporaryRoot.resolve("usage")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(usageDirectory)
            val sourcePath = sourceDirectory.resolve("Moved.kt")
            val staysPath = sourceDirectory.resolve("Stays.kt")
            val usagePath = usageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package old.pkg\n\nfun moved() = 1\n")
            Files.writeString(staysPath, "package old.pkg\n\nfun stays() = 2\n")
            Files.writeString(usagePath, "package usage\n\nimport old.pkg.*\n\nfun use() = moved() + stays()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-package-wildcard-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.pkg")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A package wildcard must keep binding declarations that stay behind",
                "package usage\n\nimport new.pkg.moved\nimport old.pkg.*\n\nfun use() = moved() + stays()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a caller's alias-bound usage needs no incoming unaliased import.
     *
     * The caller formerly shared the declaration's package, but it reaches the declaration only
     * through `import a.moved as alias`. Rewriting that directive keeps `alias()` bound, so no
     * `import b.moved` is required.
     */
    fun testApply_realSession_skipsIncomingImportForAliasBoundUsage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-incoming-alias")
        try {
            val oldPackageDirectory = temporaryRoot.resolve("old")
            Files.createDirectories(oldPackageDirectory)
            val sourcePath = oldPackageDirectory.resolve("Moved.kt")
            val callerPath = oldPackageDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package a\n\nfun moved() = 1\n")
            Files.writeString(callerPath, "package a\n\nimport a.moved as alias\n\nfun use() = alias()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-incoming-alias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("b")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The rewritten alias must be the only import the caller needs",
                "package a\n\nimport b.moved as alias\n\nfun use() = alias()\n",
                success.changedFiles[callerPath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a fully qualified caller of a declaration that moves into the root package gets an
     * explicit import.
     *
     * Replacing `old.moved()` with a root-package `moved()` leaves a bare name, and named packages
     * do not see root-package declarations implicitly.
     */
    fun testApply_realSession_addsImportWhenQualifiedTargetBecomesRootPackageName() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-root-target")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(usagePath, "package usage\n\nfun use() = old.moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-root-target-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName.ROOT),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A root-package target must be imported into a named package",
                "package usage\n\nimport moved\n\nfun use() = moved()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a rewritten qualified reference avoids a new package segment captured by a parameter.
     *
     * `dest.moved()` would resolve `dest` to the parameter instead of the package and silently
     * call a member, so the reference must be shortened and the declaration imported instead.
     */
    fun testApply_realSession_importsInsteadOfQualifyingThroughShadowedPackageName() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-shadowed-package")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(
                usagePath,
                "package usage\n\nclass Holder { fun moved() = 2 }\n" +
                    "fun use(dest: Holder) = old.moved()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-shadowed-package-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A shadowed package name must fall back to an import",
                "package usage\n\nimport dest.moved\n\nclass Holder { fun moved() = 2 }\n" +
                    "fun use(dest: Holder) = moved()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the package change is rejected when neither the package nor the bare name is usable.
     *
     * Rewriting the reference either way would call another declaration, so nothing may be staged.
     */
    fun testApply_realSession_conflictsWhenPackageAndNameAreBothShadowed() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-shadowed-both")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(
                usagePath,
                "package usage\n\nclass Holder { fun moved() = 2 }\n" +
                    "fun use(dest: Holder, moved: Int) = old.moved() + moved\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-shadowed-both-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the shadowed caller: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an explicit import would not silently retarget an unmoved overload.
     *
     * A wildcard import keeps both `choose` overloads visible, and the explicit import of the moved
     * one would take precedence over the overload that stays behind.
     */
    fun testApply_realSession_conflictsWhenImportWouldRebindUnmovedOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-overload-rebind")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Number.kt")
            val otherPath = temporaryRoot.resolve("old/Int.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nfun choose(n: Number) = 1\n")
            Files.writeString(otherPath, "package old\n\nfun choose(n: Int) = 2\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.*\n\nfun use() = choose(1) + choose(1.5)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-overload-rebind-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the rebound overload: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an index access imports both the class and the convention operator left behind.
     *
     * `box[0]` depends on a top-level `operator fun Box.get` that same-package visibility provided
     * and that has no simple-name node of its own.
     */
    fun testApply_realSession_addsImportForOutgoingArrayAccessOperator() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-array-access")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            val sourcePath = temporaryRoot.resolve("p/Moved.kt")
            Files.writeString(sourcePath, "package p\n\nfun f(box: Box) = box[0]\n")
            Files.writeString(
                temporaryRoot.resolve("p/Box.kt"),
                "package p\n\nclass Box\n\noperator fun Box.get(index: Int) = index\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-array-access-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("q")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An index access needs the operator dependency imported as well",
                "package q\n\nimport p.Box\nimport p.get\n\nfun f(box: Box) = box[0]\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a default-package caller holding only a file annotation receives the import it needs.
     *
     * A file annotation is not a declaration, so the file has no declaration boundary; the import
     * has to be appended at the end instead.
     */
    fun testApply_realSession_insertsImportIntoAnnotationOnlyRootFile() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-annotation-only")
        try {
            Files.createDirectories(temporaryRoot.resolve("source"))
            val sourcePath = temporaryRoot.resolve("source/Marker.kt")
            val usagePath = temporaryRoot.resolve("source/Usage.kt")
            Files.writeString(sourcePath, "annotation class Marker\n")
            Files.writeString(usagePath, "@file:OptIn(Marker::class)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-annotation-only-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An annotation-only caller must receive the import at its end",
                "@file:OptIn(Marker::class)\n\nimport dest.Marker",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a wildcard import is dropped when its package empties into several new packages.
     *
     * No single retargeted wildcard keeps the bindings, so explicit imports replace it and the
     * now unresolved directive is removed.
     */
    fun testApply_realSession_removesObsoletePackageWildcardWhenPackageSplits() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-wildcard-split")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val firstPath = temporaryRoot.resolve("old/First.kt")
            val secondPath = temporaryRoot.resolve("old/Second.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(firstPath, "package old\n\nfun a() = 1\n")
            Files.writeString(secondPath, "package old\n\nfun b() = 2\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.*\n\nfun use() = a() + b()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-wildcard-split-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val first = session.getKtFileForPath(firstPath.toString()) ?: error("Could not obtain first PSI")
            val second = session.getKtFileForPath(secondPath.toString()) ?: error("Could not obtain second PSI")

            val outcome = KaChangePackageComputer(first).apply(
                linkedMapOf(first to FqName("dest1"), second to FqName("dest2")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An emptied package wildcard must be removed in favour of explicit imports",
                "package usage\n\nimport dest1.a\nimport dest2.b\nfun use() = a() + b()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an aliased nested import keeps the caller a participant of the package change.
     *
     * The alias hides the usage from K2 reference discovery, and only the import path names the
     * moved class, so the directive itself has to be retargeted.
     */
    fun testApply_realSession_retargetsAliasedNestedImport() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-aliased-nested")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Outer.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nclass Outer { class Inner }\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.Outer.Inner as I\n\nval value: I? = null\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-aliased-nested-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An aliased nested import must follow the moved enclosing class",
                "package usage\n\nimport dest.Outer.Inner as I\n\nval value: I? = null\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an extension access on a variable named like the package still needs its import.
     *
     * `p.extra` spells the extension property's package, but `p` is the function's parameter, not
     * the package. Treating the spelling as a qualifier would drop the import the file now needs.
     */
    fun testApply_realSession_keepsExtensionAccessOnValueReceiverNamedLikePackage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-outgoing-receiver")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            val movedPath = temporaryRoot.resolve("p/Moved.kt")
            Files.writeString(movedPath, "package p\n\nfun read(p: String) = p.extra\n")
            Files.writeString(
                temporaryRoot.resolve("p/Extra.kt"),
                "package p\n\nval String.extra: Int get() = length\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-outgoing-receiver-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(movedPath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("q")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A value receiver spelled like the package does not make the access self-sufficient",
                "package q\n\nimport p.extra\n\nfun read(p: String) = p.extra\n",
                success.changedFiles[movedPath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a constructor call through a type alias imports the alias, not the expanded class.
     *
     * The call still spells `Alias`, so importing `Impl` would leave it unresolved even though K2
     * reports the underlying constructor.
     */
    fun testApply_realSession_importsTypeAliasUsedByConstructorCall() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-alias-constructor")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            val movedPath = temporaryRoot.resolve("old/Moved.kt")
            Files.writeString(movedPath, "package old\n\nfun make() = Alias()\n")
            Files.writeString(
                temporaryRoot.resolve("old/Alias.kt"),
                "package old\n\ntypealias Alias = Impl\n\nclass Impl\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-alias-constructor-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(movedPath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The referenced alias itself must be imported",
                "package dest\n\nimport old.Alias\n\nfun make() = Alias()\n",
                success.changedFiles[movedPath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a top-level callable read from a dependency JAR still receives its import.
     *
     * Binary declarations have no Kotlin source PSI, so the dependency on the old package can only
     * be recognised through the callable id. The stdlib function `listOf` lives in
     * `kotlin.collections`, which this file declares as its package before the move.
     */
    fun testApply_realSession_importsBinaryTopLevelCallableFromOldPackage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-binary-callable")
        try {
            val movedPath = temporaryRoot.resolve("Moved.kt")
            Files.writeString(movedPath, "package kotlin.collections\n\nfun make() = listOf(1)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-binary-callable-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(movedPath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A binary top-level callable is only identifiable through its callable id",
                "package dest\n\nimport kotlin.collections.listOf\n\nfun make() = listOf(1)\n",
                success.changedFiles[movedPath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a convention operator keeps the resolved callable name in its outgoing import.
     *
     * The operation reference spells `+` while the declaration is `plus`, so building the import
     * from the spelled name would produce the unresolvable `import p.+`.
     */
    fun testApply_realSession_importsConventionOperatorByName() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-operator-import")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            val movedPath = temporaryRoot.resolve("p/Moved.kt")
            Files.writeString(movedPath, "package p\n\nfun use(a: p.Box, b: p.Box) = a + b\n")
            Files.writeString(
                temporaryRoot.resolve("p/Box.kt"),
                "package p\n\nclass Box\n\noperator fun Box.plus(other: Box) = Box()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-operator-import-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(movedPath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("q")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A convention operator must be imported by its declared name",
                "package q\n\nimport p.plus\n\nfun use(a: p.Box, b: p.Box) = a + b\n",
                success.changedFiles[movedPath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a package wildcard stays when the package also exists in a dependency JAR.
     *
     * The Java dependency declares `old.source`, so the caller's `import old.source.*` keeps
     * binding `JavaHelper` even after the last project source leaves that package. Retargeting
     * the wildcard would leave the binary usage unresolved.
     */
    fun testApply_realSession_keepsPackageWildcardBindingDependencyDeclarations() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-binary-wildcard")
        val buildRoot = Files.createTempDirectory("nbkotlin-change-package-binary-wildcard-build")
        try {
            val javaJar = compileJavaClassJar(buildRoot, "old.source", "JavaHelper") ?: run {
                println("Java compiler unavailable — skipping binary wildcard test")
                return
            }
            Files.createDirectories(temporaryRoot.resolve("old/source"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val movedPath = temporaryRoot.resolve("old/source/Moved.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(movedPath, "package old.source\n\nfun movedByUs() = 1\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.source.*\n\n" +
                    "fun use() = movedByUs() + JavaHelper().hashCode()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-binary-wildcard-integration",
                binaryJars = listOf(stdlib, javaJar),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(movedPath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("new.pkg")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A wildcard that still binds dependency declarations must stay",
                "package usage\n\nimport new.pkg.movedByUs\nimport old.source.*\n\n" +
                    "fun use() = movedByUs() + JavaHelper().hashCode()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
            buildRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an emptied package wildcard is removed when the declarations move to the root package.
     *
     * A root package cannot be named by a wildcard, so rendering the destination would produce the
     * invalid `import .*`; the directive is removed and the explicit import stays.
     */
    fun testApply_realSession_removesObsoleteWildcardWhenTargetIsRootPackage() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-wildcard-root")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(usagePath, "package usage\n\nimport old.*\n\nfun use() = moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-wildcard-root-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName.ROOT),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A root-package destination removes the wildcard instead of rendering `.*`",
                "package usage\n\nimport moved\nfun use() = moved()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an explicit import would not silently retarget an unmoved extension overload.
     *
     * Both extensions are visible through the wildcard, and `1.choose()` selects the more specific
     * `Int` extension. Importing the moved `Number` extension explicitly makes it outrank the
     * wildcard-bound one, changing which function the call uses.
     */
    fun testApply_realSession_conflictsWhenImportWouldRebindUnmovedExtensionOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-extension-rebind")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Number.kt")
            Files.writeString(sourcePath, "package old\n\nfun Number.choose() = 1\n")
            Files.writeString(temporaryRoot.resolve("old/Int.kt"), "package old\n\nfun Int.choose() = 2\n")
            Files.writeString(
                temporaryRoot.resolve("usage/Usage.kt"),
                "package usage\n\nimport old.*\n\nfun use() = 1.choose() + 1.5.choose()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-extension-rebind-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the rebound extension call: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a package qualifier hidden by an ordinary import falls back to an explicit import.
     *
     * `import other.dest` binds the name `dest` to an object, so rewriting `old.moved()` to
     * `dest.moved()` would call that object's member. The chain is shortened instead.
     */
    fun testApply_realSession_importsInsteadOfQualifyingThroughImportedPackageName() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-imported-qualifier")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("other"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(temporaryRoot.resolve("other/Dest.kt"), "package other\n\nobject dest {\n    fun moved() = 2\n}\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport other.dest\n\nfun use() = old.moved()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-imported-qualifier-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An imported binding of the package name must fall back to an explicit import",
                "package usage\n\nimport dest.moved\nimport other.dest\n\nfun use() = moved()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies rewriting a direct import does not silently drop an overload that stays behind.
     *
     * `import old.choose` binds the whole overload set; rewriting it to `dest.choose` removes the
     * unmoved `choose(Int)`, so `choose(1)` would resolve to the moved `Number` overload instead.
     */
    fun testApply_realSession_conflictsWhenRewrittenImportLosesUnmovedOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-rewritten-import")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Number.kt")
            Files.writeString(sourcePath, "package old\n\nfun choose(n: Number) = 1\n")
            Files.writeString(temporaryRoot.resolve("old/Int.kt"), "package old\n\nfun choose(n: Int) = 2\n")
            Files.writeString(
                temporaryRoot.resolve("usage/Usage.kt"),
                "package usage\n\nimport old.choose\n\nfun use() = choose(1)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-rewritten-import-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the caller whose import loses an overload: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an emptied package wildcard is removed instead of broadened to its destination.
     *
     * Retargeting `import old.*` to `import dest.*` also imports unrelated declarations already in
     * `dest`, which can capture names that currently resolve through another wildcard import.
     */
    fun testApply_realSession_removesObsoleteWildcardInsteadOfBroadeningDestination() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-wildcard-broaden")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("lib"))
            Files.createDirectories(temporaryRoot.resolve("dest"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(temporaryRoot.resolve("lib/Lib.kt"), "package lib\n\nfun choose(n: Number) = 2\n")
            Files.writeString(temporaryRoot.resolve("dest/Existing.kt"), "package dest\n\nfun choose(n: Int) = 3\n")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.*\nimport lib.*\n\nfun use() = moved() + choose(1)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-wildcard-broaden-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An emptied wildcard is removed rather than importing the whole destination package",
                "package usage\n\nimport dest.moved\nimport lib.*\n\nfun use() = moved() + choose(1)\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies shortening to a root-package name is rejected when a member captures that name.
     *
     * `old.moved()` becomes `moved()`, but the enclosing class declares a member with that name,
     * which outranks the root-package import, so the call would silently change meaning.
     */
    fun testApply_realSession_conflictsWhenRootTargetNameIsCapturedByMember() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-root-capture")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(
                temporaryRoot.resolve("usage/Usage.kt"),
                "package usage\n\nimport old.*\n\nclass Caller {\n" +
                    "    fun moved() = 2\n" +
                    "    fun use() = old.moved()\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-root-capture-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName.ROOT),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the capturing caller: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a same-package type alias that expands to another package is still imported.
     *
     * `Alias()` resolves to the constructor of `other.Impl`, so the expanded declaration is not in
     * the package being left; the source keeps spelling `Alias`, which only the alias declaration
     * in the old package provides.
     */
    fun testApply_realSession_importsSamePackageTypeAliasExpandingElsewhere() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-expanding-alias")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            Files.createDirectories(temporaryRoot.resolve("other"))
            val sourcePath = temporaryRoot.resolve("p/Moved.kt")
            Files.writeString(sourcePath, "package p\n\nfun make() = Alias()\n")
            Files.writeString(temporaryRoot.resolve("p/Aliases.kt"), "package p\n\ntypealias Alias = other.Impl\n")
            Files.writeString(temporaryRoot.resolve("other/Impl.kt"), "package other\n\nclass Impl\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-expanding-alias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The type alias must be imported by the name the source spells",
                "package dest\n\nimport p.Alias\n\nfun make() = Alias()\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a rewritten direct import is checked even when the old package stays wildcarded.
     *
     * The caller keeps `import old.*`, which still binds the staying `Int` overload, but the
     * rewritten `import dest.choose` outranks that wildcard, so `choose(1)` would silently switch
     * to the moved `Number` overload.
     */
    fun testApply_realSession_conflictsWhenDirectImportOutranksRemainingWildcard() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-wildcard-priority")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Number.kt")
            Files.writeString(sourcePath, "package old\n\nfun choose(n: Number) = 1\n")
            Files.writeString(temporaryRoot.resolve("old/Int.kt"), "package old\n\nfun choose(n: Int) = 2\n")
            Files.writeString(
                temporaryRoot.resolve("usage/Usage.kt"),
                "package usage\n\nimport old.choose\nimport old.*\n\nfun use() = choose(1)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-wildcard-priority-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the caller whose rewritten import outranks the wildcard: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a rewritten direct import is checked even when the caller stays in the old package.
     *
     * Same-package declarations cannot outrank an explicit import, so after the redundant
     * `import old.choose` becomes `import dest.choose`, `choose(1)` would bind the moved
     * `Number` overload instead of the staying same-package `Int` one.
     */
    fun testApply_realSession_conflictsWhenDirectImportOutranksSamePackageOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-same-package-priority")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            val sourcePath = temporaryRoot.resolve("old/Number.kt")
            Files.writeString(sourcePath, "package old\n\nfun choose(n: Number) = 1\n")
            Files.writeString(temporaryRoot.resolve("old/Int.kt"), "package old\n\nfun choose(n: Int) = 2\n")
            Files.writeString(
                temporaryRoot.resolve("old/Usage.kt"),
                "package old\n\nimport old.choose\n\nfun use() = choose(1)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-same-package-priority-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the caller whose rewritten import outranks the package: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies convention operators count as references an explicit import could capture.
     *
     * `a + b` is spelled `+` but resolves to a callable named `plus`; adding an explicit import of
     * the moved `Box.plus` outranks the wildcard binding of the staying `Child.plus`, so the
     * expression would silently select the other extension.
     */
    fun testApply_realSession_conflictsWhenConventionOperatorLosesUnmovedOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-operator")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Box.kt")
            Files.writeString(
                sourcePath,
                "package old\n\nopen class Box\n\noperator fun Box.plus(other: Box) = 1\n",
            )
            Files.writeString(
                temporaryRoot.resolve("old/Child.kt"),
                "package old\n\nclass Child : Box()\n\noperator fun Child.plus(other: Box) = 2\n",
            )
            Files.writeString(
                temporaryRoot.resolve("usage/Usage.kt"),
                "package usage\n\nimport old.*\n\nfun use(a: Child, b: Box) = (a + b) + (b + b)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-operator-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the caller whose operator reference would be captured: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a destination package segment bound by a sibling file is not used as a qualifier.
     *
     * The caller's package declares `object dest` in another file, and same-package declarations
     * are visible without an import, so `dest.moved()` would select that object's member instead
     * of the moved function. The reference is shortened and the declaration imported instead.
     */
    fun testApply_realSession_shortensWhenSamePackageObjectCapturesDestinationSegment() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-sibling-segment")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(temporaryRoot.resolve("usage/Helper.kt"), "package usage\n\nobject dest {\n    fun moved() = 2\n}\n")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(usagePath, "package usage\n\nfun use() = old.moved()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-sibling-segment-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The captured package segment must be replaced by an import of the moved declaration",
                "package usage\n\nimport dest.moved\n\nfun use() = moved()\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an explicitly imported type alias stays bound when its declaring file moves.
     *
     * `Alias()` resolves to the constructor of the unmoved `p.Impl`, but the source spells the
     * alias from `other`; that import stays valid after the package change, so no unresolved
     * `p.Alias` may be synthesized.
     */
    fun testApply_realSession_keepsExplicitlyImportedTypeAlias() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-imported-alias")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            Files.createDirectories(temporaryRoot.resolve("other"))
            val sourcePath = temporaryRoot.resolve("p/Move.kt")
            Files.writeString(sourcePath, "package p\n\nimport other.Alias\n\nfun make() = Alias()\n")
            Files.writeString(temporaryRoot.resolve("other/Aliases.kt"), "package other\n\ntypealias Alias = p.Impl\n")
            Files.writeString(temporaryRoot.resolve("p/Impl.kt"), "package p\n\nclass Impl\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-imported-alias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The existing import of the alias already binds the name",
                "package dest\n\nimport other.Alias\n\nfun make() = Alias()\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a rewritten aliased import is checked for the overloads it stops binding.
     *
     * `import old.choose as pick` binds the whole overload set, so retargeting it to
     * `import dest.choose as pick` while the `Int` overload stays behind would silently make
     * `pick(1)` select the moved `Number` overload.
     */
    fun testApply_realSession_conflictsWhenAliasedImportLosesUnmovedOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-aliased-import")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Number.kt")
            Files.writeString(sourcePath, "package old\n\nfun choose(n: Number) = 1\n")
            Files.writeString(temporaryRoot.resolve("old/Int.kt"), "package old\n\nfun choose(n: Int) = 2\n")
            Files.writeString(
                temporaryRoot.resolve("usage/Usage.kt"),
                "package usage\n\nimport old.choose as pick\n\nfun use() = pick(1)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-aliased-import-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the caller whose aliased import loses an overload: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a companion-object extension call is treated as import-sensitive.
     *
     * `Box.choose(1.5)` and `Box.choose(1)` both bind extensions through overload resolution even
     * though the receiver spells a class name. Adding `import dest.choose` for the moved `Number`
     * overload outranks the wildcard binding of the staying `Int` overload, so `Box.choose(1)`
     * would silently select the moved one.
     */
    fun testApply_realSession_conflictsWhenCompanionExtensionLosesUnmovedOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-companion-extension")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Box.kt")
            Files.writeString(
                sourcePath,
                "package old\n\nclass Box {\n    companion object\n}\n\n" +
                    "fun Box.Companion.choose(n: Number) = 1\n",
            )
            Files.writeString(
                temporaryRoot.resolve("old/Int.kt"),
                "package old\n\nfun Box.Companion.choose(n: Int) = 2\n",
            )
            Files.writeString(
                temporaryRoot.resolve("usage/Usage.kt"),
                "package usage\n\nimport old.*\n\nfun use() = Box.choose(1) + Box.choose(1.5)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-companion-extension-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the caller whose companion extension would be captured: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a destructuring declaration's `componentN` operator is imported on a package change.
     *
     * `val (x) = box` calls the top-level `operator fun Box.component1`, which the file only sees
     * through same-package visibility. After the move the convention function must be imported
     * explicitly, exactly like a named reference.
     */
    fun testApply_realSession_importsDestructuringOperator() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-destructuring")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            val sourcePath = temporaryRoot.resolve("p/Move.kt")
            Files.writeString(sourcePath, "package p\n\nfun use(box: Box): Int {\n    val (x) = box\n    return x\n}\n")
            Files.writeString(
                temporaryRoot.resolve("p/Box.kt"),
                "package p\n\nclass Box\n\noperator fun Box.component1() = 1\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-destructuring-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("q")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The destructuring convention operator must be imported by its declared name",
                "package q\n\nimport p.Box\nimport p.component1\n\n" +
                    "fun use(box: Box): Int {\n    val (x) = box\n    return x\n}\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an outgoing import that would capture a moving overload is reported.
     *
     * The caller moves to `dest` together with the `Int` overload while the `Number` overload
     * stays in `old`. Keeping the latter visible requires `import old.choose`, but that explicit
     * import outranks the moved `dest.choose(Int)`, so `choose(1)` would silently switch overload.
     */
    fun testApply_realSession_conflictsWhenOutgoingImportCapturesMovingOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-outgoing-capture")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            val intPath = temporaryRoot.resolve("old/Int.kt")
            Files.writeString(intPath, "package old\n\nfun choose(n: Int) = 2\n")
            Files.writeString(temporaryRoot.resolve("old/Number.kt"), "package old\n\nfun choose(n: Number) = 1\n")
            val usagePath = temporaryRoot.resolve("old/Usage.kt")
            Files.writeString(usagePath, "package old\n\nfun use() = choose(1) + choose(1.5)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-outgoing-capture-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val intSource = session.getKtFileForPath(intPath.toString()) ?: error("Could not obtain source PSI")
            val usageSource = session.getKtFileForPath(usagePath.toString()) ?: error("Could not obtain usage PSI")

            val outcome = KaChangePackageComputer(usageSource).apply(
                mapOf(intSource to FqName("dest"), usageSource to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the caller whose outgoing import would capture a moved overload: " +
                    conflicts.messages,
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a destination package segment inherited from a superclass is not treated as free.
     *
     * `Base` exposes `val dest: Holder` and `Holder` has its own `moved()`, so rewriting
     * `old.moved()` to `dest.moved()` would call the holder's member. The chain must be shortened
     * and the moved declaration imported instead.
     */
    fun testApply_realSession_shortensWhenDestinationSegmentIsInherited() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-inherited-segment")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(
                temporaryRoot.resolve("usage/Base.kt"),
                "package usage\n\nopen class Base {\n    val dest: Holder = Holder()\n}\n\n" +
                    "class Holder {\n    fun moved() = 2\n}\n",
            )
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(
                usagePath,
                "package usage\n\nclass Caller : Base() {\n    fun use() = old.moved()\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-inherited-segment-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An inherited destination segment must be replaced by an import of the moved declaration",
                "package usage\n\nimport dest.moved\n\nclass Caller : Base() {\n    fun use() = moved()\n}\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a for loop imports the convention operator left behind.
     *
     * `for (x in box)` spells no name for `iterator`, which same-package visibility provided; after
     * the move, that top-level function must be imported explicitly or the loop stops compiling.
     */
    fun testApply_realSession_addsImportForOutgoingForLoopIterator() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-for-loop")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            val sourcePath = temporaryRoot.resolve("p/Moved.kt")
            Files.writeString(sourcePath, "package p\n\nfun use(box: Box) {\n    for (x in box) println(x)\n}\n")
            Files.writeString(
                temporaryRoot.resolve("p/Box.kt"),
                "package p\n\nclass Box\n\noperator fun Box.iterator(): Iterator<Int> = emptyList<Int>().iterator()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-for-loop-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("q")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "A for loop needs the iterator operator dependency imported as well",
                "package q\n\nimport p.Box\nimport p.iterator\n\n" +
                    "fun use(box: Box) {\n    for (x in box) println(x)\n}\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a type alias whose name equals the expanded class is still imported.
     *
     * `typealias Impl = other.Impl` is invisible after the move, so `Impl()` needs `import p.Impl`
     * even though the resolved constructor's simple name already matches the spelled name.
     */
    fun testApply_realSession_importsSameNamedTypeAliasExpandingElsewhere() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-same-named-alias")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            Files.createDirectories(temporaryRoot.resolve("other"))
            val sourcePath = temporaryRoot.resolve("p/Move.kt")
            Files.writeString(sourcePath, "package p\n\nfun make() = Impl()\n")
            Files.writeString(temporaryRoot.resolve("p/Aliases.kt"), "package p\n\ntypealias Impl = other.Impl\n")
            Files.writeString(temporaryRoot.resolve("other/Impl.kt"), "package other\n\nclass Impl\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-same-named-alias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The type alias must be imported by the name the source spells",
                "package dest\n\nimport p.Impl\n\nfun make() = Impl()\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an explicit import of a moved `get` operator does not silently retarget `n[0]`.
     *
     * `import old.*` binds both the `Int` operator that stays behind and the moved `Number` one,
     * and the indexed accesses on a `Double` and an `Int` select different overloads. Adding
     * `import dest.get` makes the explicit import outrank the wildcard, so `n[0]` would start
     * calling the moved `Number` extension.
     */
    fun testApply_realSession_conflictsWhenImportWouldRebindArrayAccessOperator() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-array-conflict")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Number.kt")
            Files.writeString(sourcePath, "package old\n\noperator fun Number.get(index: Int) = 1\n")
            Files.writeString(temporaryRoot.resolve("old/Int.kt"), "package old\n\noperator fun Int.get(index: Int) = 2\n")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(
                usagePath,
                "package usage\n\nimport old.*\n\nfun use(n: Int) = (1.5).get(0) + n[0]\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-array-conflict-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected conflicts, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Conflicts)
            val conflicts = outcome as KaChangePackageComputer.ApplyOutcome.Conflicts
            assertTrue(
                "The conflict must name the rebinding caller: ${conflicts.messages}",
                conflicts.messages.any { message -> message.contains("Usage.kt") },
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an object receiver is not mistaken for a package qualifier.
     *
     * `object p` in package `p` makes `p.extra()` a value-receiver extension call, so moving the
     * caller must import both the object and its extension, not treat `p` as the package spelling.
     */
    fun testApply_realSession_importsObjectReceiverExtension() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-object-receiver")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            val sourcePath = temporaryRoot.resolve("p/Moved.kt")
            Files.writeString(sourcePath, "package p\n\nfun use() = p.extra()\n")
            Files.writeString(temporaryRoot.resolve("p/Helpers.kt"), "package p\n\nobject p\n\nfun p.extra() = 1\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-object-receiver-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "An object receiver keeps the extension dependent on an import",
                "package dest\n\nimport p.p\nimport p.extra\n\nfun use() = p.extra()\n",
                success.changedFiles[sourcePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies both operators of an indexed update follow the moved file.
     *
     * `box[0]++` calls `get` and `set` through one convention reference, and neither name is
     * spelled in the source. Both extensions stay in the former package, so the moved file needs
     * an import for each of them.
     */
    fun testApply_realSession_importsBothIndexedAccessOperators() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-indexed-update")
        try {
            Files.createDirectories(temporaryRoot.resolve("p"))
            val sourcePath = temporaryRoot.resolve("p/Moved.kt")
            Files.writeString(sourcePath, "package p\n\nfun bump(box: Box) { box[0]++; box[0] += 1 }\n")
            Files.writeString(
                temporaryRoot.resolve("p/Box.kt"),
                "package p\n\nclass Box\n\noperator fun Box.get(index: Int) = 0\n\n" +
                    "operator fun Box.set(index: Int, value: Int) {}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-indexed-update-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            val movedText = success.changedFiles[sourcePath.toString()].orEmpty()
            assertTrue("The indexed update needs both operators imported:\n$movedText", "import p.get" in movedText)
            assertTrue("The indexed update needs both operators imported:\n$movedText", "import p.set" in movedText)
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an unused wildcard import of a vacated package is discovered and removed.
     *
     * The caller references no moved declaration, so usage search never reports it and only the
     * wildcard directive itself names the old package. Leaving it in place produces an unresolved
     * import once the last declaration leaves the package.
     */
    fun testApply_realSession_removesUnusedOldPackageWildcard() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping Change Package integration test")
            return
        }
        val temporaryRoot = Files.createTempDirectory("nbkotlin-change-package-obsolete-wildcard")
        try {
            Files.createDirectories(temporaryRoot.resolve("old"))
            Files.createDirectories(temporaryRoot.resolve("usage"))
            val sourcePath = temporaryRoot.resolve("old/Moved.kt")
            val usagePath = temporaryRoot.resolve("usage/Usage.kt")
            Files.writeString(sourcePath, "package old\n\nfun moved() = 1\n")
            Files.writeString(usagePath, "package usage\n\nimport old.*\n\nfun use() = 2\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "change-package-obsolete-wildcard-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(temporaryRoot),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val outcome = KaChangePackageComputer(source).apply(
                mapOf(source to FqName("dest")),
                updateReferences = true,
            )

            assertTrue("Expected Change Package success, got $outcome", outcome is KaChangePackageComputer.ApplyOutcome.Success)
            val success = outcome as KaChangePackageComputer.ApplyOutcome.Success
            assertEquals(
                "The wildcard import of a package that lost every declaration must be removed",
                "package usage\n\nfun use() = 2\n",
                success.changedFiles[usagePath.toString()],
            )
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    /** Locates Kotlin stdlib supplied by the Maven test runtime. */
    private fun findStdlibJar(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /**
     * Compiles [packageName].[className] with the JDK compiler and packs it into a JAR.
     *
     * @param directory scratch directory owned by the caller
     * @param packageName Java package of the generated class
     * @param className simple name of the generated class
     * @return the compiled JAR, or `null` when no JDK compiler is available
     */
    private fun compileJavaClassJar(directory: Path, packageName: String, className: String): Path? {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: return null
        val packagePath = packageName.replace('.', '/')
        val sourceFile = directory.resolve("src/$packagePath/$className.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, "package $packageName;\n\npublic class $className {}\n")
        val classesDirectory = directory.resolve("classes")
        Files.createDirectories(classesDirectory)
        var compiled = false
        compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8).use { fileManager ->
            val units = fileManager.getJavaFileObjectsFromFiles(listOf(sourceFile.toFile()))
            compiled = compiler.getTask(
                null,
                fileManager,
                null,
                listOf("-d", classesDirectory.toString()),
                null,
                units,
            ).call()
        }
        if (!compiled) return null
        val jarPath = directory.resolve("$className.jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.putNextEntry(JarEntry("$packagePath/$className.class"))
            Files.copy(classesDirectory.resolve("$packagePath/$className.class"), jar)
            jar.closeEntry()
        }
        return jarPath
    }
}
