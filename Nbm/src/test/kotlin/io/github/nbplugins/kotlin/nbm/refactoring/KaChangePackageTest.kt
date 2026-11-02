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
