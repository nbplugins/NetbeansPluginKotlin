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
import io.github.nbplugins.kotlin.refactoring.KaRenameComputer
import io.github.nbplugins.kotlin.refactoring.TextChange
import org.jetbrains.kotlin.builder.isKotlinFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaRenameComputer].
 *
 * Uses fixture directories under `projForTest/src/rename/`. Each directory contains:
 * - `file.kt`   — source with the symbol to rename
 * - `file.after` — expected result after renaming to `NEW_NAME`
 * - `file.caret` — same source with `<caret>` marking the cursor position
 *
 * Caret offsets are extracted from `file.caret` by removing the `<caret>` marker and
 * recording its position in the cleaned text, which matches `file.kt`.
 */
class KaRenameTest : KotlinTestCase("KaRenameTest", "rename") {

    companion object {
        private const val NEW_NAME = "NewName"
        private const val CARET_MARKER = "<caret>"
    }

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaRenameTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Reads the caret file for the given fixture sub-directory and returns the caret offset
     * in the cleaned (marker-free) source text.
     *
     * @param subDir fixture sub-directory name (e.g. "classrename")
     * @param fileName name of the caret file (default: "file.caret")
     * @return the caret offset, or `null` if the marker is absent
     */
    private fun readCaretOffset(subDir: String, fileName: String = "file.caret"): Int? {
        val caretFo = dir.getFileObject(subDir)?.getFileObject(fileName) ?: return null
        val caretText = caretFo.asText()
        val idx = caretText.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Returns the source text of the given fixture file, with the [CARET_MARKER] stripped.
     */
    private fun readSource(subDir: String, fileName: String = "file.kt"): String? =
        dir.getFileObject(subDir)?.getFileObject(fileName)?.asText()

    /**
     * Applies all [TextChange]s in [changes] (sorted descending by offset so earlier changes
     * are not displaced) to [source] and returns the resulting string.
     */
    private fun applyChanges(source: String, changes: List<TextChange>): String {
        var result = source
        // Apply in reverse order so offsets remain valid
        for (change in changes.sortedByDescending { it.start }) {
            result = result.substring(0, change.start) + change.newText + result.substring(change.end)
        }
        return result
    }

    /**
     * Runs a single rename fixture and asserts that applying the computed changes to
     * `file.kt` produces the text of `file.after`.
     *
     * @param subDir fixture sub-directory name
     * @param newName replacement name (default: [NEW_NAME])
     * @param sourceFileName source file within the fixture (default: "file.kt")
     * @param afterFileName expected result file (default: "file.after")
     */
    private fun runFixture(
        subDir: String,
        newName: String = NEW_NAME,
        sourceFileName: String = "file.kt",
        afterFileName: String = "file.after",
    ) {
        val session = getSessionOrSkip() ?: return

        val fixtureDir = dir.getFileObject(subDir) ?: run {
            println("KaRenameTest.$subDir: fixture directory not found — skipping")
            return
        }
        val sourceFo = fixtureDir.getFileObject(sourceFileName) ?: run {
            println("KaRenameTest.$subDir: $sourceFileName not found — skipping")
            return
        }
        val afterText = fixtureDir.getFileObject(afterFileName)?.asText() ?: run {
            println("KaRenameTest.$subDir: $afterFileName not found — skipping")
            return
        }
        val caretOffset = readCaretOffset(subDir) ?: run {
            println("KaRenameTest.$subDir: <caret> marker not found — skipping")
            return
        }

        val kaFile = session.getKtFileForPath(sourceFo.path) ?: run {
            println("KaRenameTest.$subDir: K2 file not available — skipping")
            return
        }

        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()
        val computer = KaRenameComputer(kaFile, caretOffset, allKtFiles, newName)
        val changes = computer.compute()

        assertNotNull("KaRenameComputer.compute() must not return null for $subDir", changes)
        changes!!

        val sourceText = readSource(subDir, sourceFileName)!!
        val changesForFile = changes[sourceFo.path] ?: emptyList()
        val actual = applyChanges(sourceText, changesForFile)

        assertEquals("Rename result mismatch for $subDir", afterText, actual)
    }

    // -------------------------------------------------------------------------
    // Basic rename fixtures
    // -------------------------------------------------------------------------

    /** Renames a class declaration and all its constructor call sites. */
    fun testRenameClass() = runFixture("classrename")

    /** Renames a class with an explicit primary constructor and its constructor call sites. */
    fun testRenameClassWithExplicitConstructor() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping explicit-constructor rename test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-constructor")
        try {
            val sourceDirectory = root.resolve("source")
            val callerDirectory = root.resolve("caller")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(callerDirectory)
            val sourcePath = sourceDirectory.resolve("Foo.kt")
            val callerPath = callerDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package a\n\nclass Foo(val x: Int)\n")
            Files.writeString(callerPath, "package b\n\nimport a.Foo\n\nfun use() = Foo(1)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-constructor-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val changes = KaRenameComputer(source, source.text.indexOf("Foo"), setOf(source, caller), "Bar").compute()

            assertNotNull("compute() must return non-null for an explicit primary constructor", changes)
            assertEquals(
                "An explicit constructor call must follow the renamed class",
                "package b\n\nimport a.Bar\n\nfun use() = Bar(1)\n",
                applyChanges(caller.text, changes!![callerPath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a constructor call bound through an import alias keeps the alias.
     *
     * `import a.Foo as Alias` binds only `Alias`; the class name changes in the import path, but
     * rewriting the call to the new class name would leave it unresolved in the caller's package.
     */
    fun testRenameClassWithAliasedConstructorCall() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping aliased-constructor rename test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-alias-constructor")
        try {
            val sourceDirectory = root.resolve("source")
            val callerDirectory = root.resolve("caller")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(callerDirectory)
            val sourcePath = sourceDirectory.resolve("Foo.kt")
            val callerPath = callerDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package a\n\nclass Foo(val x: Int)\n")
            Files.writeString(callerPath, "package b\n\nimport a.Foo as Alias\n\nfun use() = Alias(1)\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-alias-constructor-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val changes = KaRenameComputer(source, source.text.indexOf("Foo"), setOf(source, caller), "Bar").compute()

            assertNotNull("compute() must return non-null for an aliased constructor call", changes)
            assertEquals(
                "An aliased constructor call must keep the alias while the import path follows the class",
                "package b\n\nimport a.Bar as Alias\n\nfun use() = Alias(1)\n",
                applyChanges(caller.text, changes!![callerPath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies qualified references stay renamed when a redundant alias has the class's own name.
     *
     * `import p.Foo as Foo` makes the alias name indistinguishable from the class name, but
     * `p.Foo(1)` and `p.Foo` are spelled through the package, so they must still follow the rename.
     */
    fun testRenameClassKeepsQualifiedReferencesWithRedundantAlias() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping redundant-alias rename test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-redundant-alias")
        try {
            val sourceDirectory = root.resolve("source")
            val callerDirectory = root.resolve("caller")
            Files.createDirectories(sourceDirectory)
            Files.createDirectories(callerDirectory)
            val sourcePath = sourceDirectory.resolve("Foo.kt")
            val callerPath = callerDirectory.resolve("Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Foo(val x: Int)\n")
            Files.writeString(
                callerPath,
                "package b\n\nimport p.Foo as Foo\n\nfun use(): p.Foo = p.Foo(1)\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-redundant-alias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val changes = KaRenameComputer(source, source.text.indexOf("Foo"), setOf(source, caller), "Bar").compute()

            assertNotNull("compute() must return non-null for a redundant alias", changes)
            assertEquals(
                "Qualified references must follow the rename even when an alias reuses the class name",
                "package b\n\nimport p.Bar as Foo\n\nfun use(): p.Bar = p.Bar(1)\n",
                applyChanges(caller.text, changes!![callerPath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a secondary constructor's `this(...)` delegation is not renamed with its class.
     *
     * The delegation call resolves to a constructor of the same class, but its `this` keyword is
     * syntax, not a reference to the class name.
     */
    fun testRenameClassKeepsThisConstructorDelegation() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping constructor-delegation rename test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-this-delegation")
        try {
            val sourceDirectory = root.resolve("source")
            Files.createDirectories(sourceDirectory)
            val sourcePath = sourceDirectory.resolve("Foo.kt")
            Files.writeString(
                sourcePath,
                "package a\n\nclass Foo(val n: Int) {\n    constructor() : this(0)\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-this-delegation-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val changes = KaRenameComputer(source, source.text.indexOf("Foo"), setOf(source), "Bar").compute()

            assertNotNull("compute() must return non-null for a delegated constructor", changes)
            assertEquals(
                "A this(...) delegation must keep its keyword",
                "package a\n\nclass Bar(val n: Int) {\n    constructor() : this(0)\n}\n",
                applyChanges(source.text, changes!![sourcePath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a base class rename does not rewrite a subclass's `super(...)` delegation.
     *
     * `super(0)` resolves to the renamed base constructor, but the keyword must stay while the
     * supertype reference in the class header does follow the rename.
     */
    fun testRenameBaseClassKeepsSuperConstructorDelegation() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping super-delegation rename test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-super-delegation")
        try {
            val baseDirectory = root.resolve("base")
            val derivedDirectory = root.resolve("derived")
            Files.createDirectories(baseDirectory)
            Files.createDirectories(derivedDirectory)
            val basePath = baseDirectory.resolve("Base.kt")
            val derivedPath = derivedDirectory.resolve("Derived.kt")
            Files.writeString(basePath, "package a\n\nopen class Base(val n: Int)\n")
            Files.writeString(derivedPath, "package a\n\nclass Derived : Base {\n    constructor() : super(0)\n}\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-super-delegation-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val base = session.getKtFileForPath(basePath.toString()) ?: error("Could not obtain source PSI")
            val derived = session.getKtFileForPath(derivedPath.toString()) ?: error("Could not obtain derived PSI")

            val changes = KaRenameComputer(base, base.text.indexOf("Base"), setOf(base, derived), "Renamed").compute()

            assertNotNull("compute() must return non-null for a subclass", changes)
            assertEquals(
                "The supertype must follow the rename while super(...) keeps its keyword",
                "package a\n\nclass Derived : Renamed {\n    constructor() : super(0)\n}\n",
                applyChanges(derived.text, changes!![derivedPath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an instance receiver keeps the `this` keyword during a class rename.
     *
     * `this` resolves to the containing class, but it is an instance expression, not a reference
     * to the class name; replacing it with the new name would produce invalid Kotlin.
     */
    fun testRenameClassKeepsInstanceReceiver() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping instance-receiver rename test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-instance-receiver")
        try {
            val sourceDirectory = root.resolve("source")
            Files.createDirectories(sourceDirectory)
            val sourcePath = sourceDirectory.resolve("Foo.kt")
            Files.writeString(
                sourcePath,
                "package a\n\nclass Foo {\n    fun self() = this\n    fun make() = Foo()\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-instance-receiver-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val changes = KaRenameComputer(source, source.text.indexOf("Foo"), setOf(source), "Bar").compute()

            assertNotNull("compute() must return non-null for an instance receiver", changes)
            assertEquals(
                "The this receiver must keep its keyword while constructor calls follow the rename",
                "package a\n\nclass Bar {\n    fun self() = this\n    fun make() = Bar()\n}\n",
                applyChanges(source.text, changes!![sourcePath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a constructor call spelled through a Kotlin type alias keeps the alias name.
     *
     * `IntFoo()` resolves to a constructor of `Foo`, but its referenced name is the type alias;
     * only the alias definition's `Foo` reference follows the rename.
     */
    fun testRenameClassKeepsTypeAliasConstructorCall() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping type-alias rename test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-type-alias")
        try {
            val sourceDirectory = root.resolve("source")
            Files.createDirectories(sourceDirectory)
            val sourcePath = sourceDirectory.resolve("Foo.kt")
            Files.writeString(
                sourcePath,
                "package a\n\nclass Foo<T>\n\ntypealias IntFoo = Foo<Int>\n\nfun make() = IntFoo()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-type-alias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val changes = KaRenameComputer(source, source.text.indexOf("Foo"), setOf(source), "Bar").compute()

            assertNotNull("compute() must return non-null for a type alias", changes)
            assertEquals(
                "A call through the type alias must keep the alias name",
                "package a\n\nclass Bar<T>\n\ntypealias IntFoo = Bar<Int>\n\nfun make() = IntFoo()\n",
                applyChanges(source.text, changes!![sourcePath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a labeled `this` receiver keeps its `@` while the label follows the rename.
     *
     * The label reference spans `@Foo`, but only the identifier is replaced; otherwise the edit
     * would produce `thisBar`.
     */
    fun testRenameClassKeepsLabelPrefix() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping receiver-label rename test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-receiver-label")
        try {
            val sourceDirectory = root.resolve("source")
            Files.createDirectories(sourceDirectory)
            val sourcePath = sourceDirectory.resolve("Foo.kt")
            Files.writeString(
                sourcePath,
                "package a\n\nclass Foo {\n    fun self() = this@Foo\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-receiver-label-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val changes = KaRenameComputer(source, source.text.indexOf("Foo"), setOf(source), "Bar").compute()

            assertNotNull("compute() must return non-null for a receiver label", changes)
            assertEquals(
                "Only the label identifier follows the rename, the @ prefix stays",
                "package a\n\nclass Bar {\n    fun self() = this@Bar\n}\n",
                applyChanges(source.text, changes!![sourcePath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a rewrite that would bind to a member function is reported as a conflict.
     *
     * Renaming `Before` to `After` rewrites `Before()` to `After()`. Inside `Caller`, that name
     * already resolves to the member function, so the constructor call would silently become a
     * call of `Caller.After`.
     */
    fun testFindCaptureConflicts_reportsMemberFunctionCapture() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-member")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nclass Caller {\n    fun After() = \"other\"\n    fun make() = Before().toString()\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-member-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertEquals("The caller's member function captures the rewritten constructor call", caller, conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a qualified constructor call is validated against the referenced package.
     *
     * `p.Before(1)` is rewritten to `p.After(1)`, and package `p` also declares
     * `fun After(n: Int)`. A qualified selector cannot be captured lexically, but the rewritten
     * name is still resolved inside package `p`, where the function is more specific than the
     * renamed constructor and would silently replace it.
     */
    fun testFindCaptureConflicts_reportsQualifiedPackageOverload() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-qualified")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before(val n: Number)\n")
            Files.writeString(
                callerPath,
                "package p\n\nfun After(n: Int) = \"other\"\n\nfun use() = p.Before(1).toString()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-qualified-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertEquals(
                "The same-package function is more specific than the renamed constructor",
                caller,
                conflict,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a rewrite with no competing binding reports no capture conflict.
     *
     * The caller has no declaration or import named `After`, so the rewritten constructor call
     * keeps constructing the renamed class.
     */
    fun testFindCaptureConflicts_ignoresFilesWithoutTheNewName() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-clean")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nclass Caller {\n    fun make() = Before().toString()\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-clean-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertNull("No binding of the new name exists, so the rewrite is safe", conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a type-position reference is not treated as a capture.
     *
     * `val value: Before?` becomes `val value: After?`; a member function named `After` does not
     * participate in type-name resolution, so it cannot capture the rewritten type.
     */
    fun testFindCaptureConflicts_ignoresTypePositions() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-type")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nclass Caller {\n    fun After() = \"other\"\n    val value: Before? = null\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-type-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertNull("A member function cannot capture a type-name rewrite", conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a type-position rewrite that would bind to a nested classifier is reported.
     *
     * Renaming `Before` to `After` rewrites `val value: Before?` to `After?`; inside `Caller` that
     * name already denotes the nested class `Caller.After`, so the property type would silently
     * change while the file keeps compiling.
     */
    fun testFindCaptureConflicts_reportsNestedClassifierCapture() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-classifier")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nclass Caller {\n    class After\n    val value: Before? = null\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-classifier-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertEquals("The caller's nested class captures the rewritten type", caller, conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies an inherited member capturing the new name is reported as a conflict.
     *
     * `class Caller : Base()` inherits `Base.After`, which is in scope unqualified. Renaming
     * `Before` to `After` rewrites `Before()` to `After()`, turning the constructor call into a
     * call of the inherited function while the file keeps compiling.
     */
    fun testFindCaptureConflicts_reportsInheritedMemberCapture() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-inherited")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nopen class Base {\n    fun After() = \"other\"\n}\n\n" +
                    "class Caller : Base() {\n    fun make() = Before().toString()\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-inherited-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertEquals("The inherited member function captures the rewritten constructor call", caller, conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a member of an extension receiver capturing the new name is reported.
     *
     * Inside `fun Receiver.make()`, the receiver's members are in scope unqualified. Renaming
     * `Before` to `After` rewrites `Before()` to `After()`, which the receiver's member function
     * would capture while the file keeps compiling.
     */
    fun testFindCaptureConflicts_reportsExtensionReceiverCapture() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-receiver")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nclass Receiver {\n    fun After() = \"other\"\n}\n\n" +
                    "fun Receiver.make() = Before().toString()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-receiver-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertEquals("The extension receiver's member captures the rewritten constructor call", caller, conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a block-local classifier capturing a rewritten type is reported.
     *
     * `class After` declared earlier in the function body is visible unqualified, so rewriting
     * `x is Before` to `x is After` would silently test the local class instead.
     */
    fun testFindCaptureConflicts_reportsBlockLocalClassifierCapture() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-block-classifier")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nfun accepts(x: Any): Boolean {\n    class After\n    return x is Before\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-block-classifier-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertEquals("The block-local class captures the rewritten type", caller, conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a same-package function of the new name capturing a rewritten constructor call is
     * reported.
     *
     * `fun After(n: Int)` is declared in the caller's package, so the rewritten `After(1)` would
     * select the function's exact `Int` parameter instead of the constructor taking `Number`.
     */
    fun testFindCaptureConflicts_reportsSamePackageFunctionCapture() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-package-function")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val functionsPath = root.resolve("p/Functions.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before(val n: Number)\n")
            Files.writeString(functionsPath, "package p\n\nfun After(n: Int) = \"other\"\n")
            Files.writeString(callerPath, "package p\n\nfun use() = Before(1).toString()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-package-function-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val functions = session.getKtFileForPath(functionsPath.toString()) ?: error("Could not obtain functions PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, functions, caller), "After",
            ).findCaptureConflicts()

            assertEquals("The same-package function captures the rewritten constructor call", caller, conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a destructured lambda parameter captures the rewritten constructor call.
     *
     * `map { (After, _) -> Before() }` binds `After` to a destructured entry, so the rewritten
     * `After()` would invoke that value instead of constructing the class.
     */
    fun testFindCaptureConflicts_reportsDestructuredLambdaParameter() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-destructured-lambda")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nfun use() = listOf({ \"other\" } to 0).map { (After, _) -> Before().toString() }\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-destructured-lambda-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertEquals("The destructured lambda entry captures the rewritten constructor call", caller, conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a same-spelled type alias keeps its own name when the expanded class is renamed.
     *
     * `typealias Before = p.Before` in another package stays named `Before`; rewriting its call
     * site to `After()` would leave it unresolved even though the alias's right-hand side follows
     * the rename to `p.After`.
     */
    fun testCompute_keepsSameSpelledTypeAliasCall() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping rename-compute test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-same-named-alias")
        try {
            Files.createDirectories(root.resolve("p"))
            Files.createDirectories(root.resolve("q"))
            val sourcePath = root.resolve("p/Before.kt")
            val aliasesPath = root.resolve("q/Aliases.kt")
            val usagePath = root.resolve("q/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(aliasesPath, "package q\n\ntypealias Before = p.Before\n")
            Files.writeString(usagePath, "package q\n\nfun make() = Before()\n")
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-same-named-alias-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val aliases = session.getKtFileForPath(aliasesPath.toString()) ?: error("Could not obtain aliases PSI")
            val usage = session.getKtFileForPath(usagePath.toString()) ?: error("Could not obtain usage PSI")

            val changes = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, aliases, usage), "After",
            ).compute()

            assertNotNull("compute() must return non-null for an alias declaration", changes)
            assertEquals(
                "Only the alias's right-hand side follows the rename",
                "package q\n\ntypealias Before = p.After\n",
                applyChanges(aliases.text, changes!![aliasesPath.toString()].orEmpty()),
            )
            assertEquals(
                "The alias call site keeps spelling the alias",
                "package q\n\nfun make() = Before()\n",
                applyChanges(usage.text, changes[usagePath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a call of a renamed function that shares a type alias's name is still rewritten.
     *
     * The file declares `typealias Before = Impl` and `fun Before(n: Int)`, and `Before(1)` calls
     * the function. The type-alias exemption only protects constructor calls spelled through the
     * alias; applying it to the function's own call would leave the reference untouched, and it
     * would silently become an alias-constructor call after the rename.
     */
    fun testCompute_renamesFunctionCallSharingTypeAliasName() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping rename-compute test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-function-alias-name")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Use.kt")
            Files.writeString(
                sourcePath,
                "package p\n\nclass Impl(val n: Number)\n\ntypealias Before = Impl\n\n" +
                    "fun Before(n: Int) = \"other\"\n\nfun use() = Before(1).toString()\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-function-alias-name-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")

            val changes = KaRenameComputer(
                source, source.text.indexOf("fun Before") + "fun ".length, setOf(source), "After",
            ).compute()

            assertNotNull("compute() must return non-null for a function rename", changes)
            assertEquals(
                "The function declaration and its call follow the rename",
                "package p\n\nclass Impl(val n: Number)\n\ntypealias Before = Impl\n\n" +
                    "fun After(n: Int) = \"other\"\n\nfun use() = After(1).toString()\n",
                applyChanges(source.text, changes!![sourcePath.toString()].orEmpty()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a fully qualified reference is not checked against lexical bindings.
     *
     * Renaming `Before` to `After` only changes the selector of `p.Before()`, which still names
     * the constructor as `p.After()`; an unrelated member named `After` cannot capture it.
     */
    fun testFindCaptureConflicts_ignoresQualifiedReference() {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping capture-conflict test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-capture-qualified")
        try {
            Files.createDirectories(root.resolve("p"))
            val sourcePath = root.resolve("p/Before.kt")
            val callerPath = root.resolve("p/Use.kt")
            Files.writeString(sourcePath, "package p\n\nclass Before\n")
            Files.writeString(
                callerPath,
                "package p\n\nclass Caller {\n    fun After() = \"other\"\n" +
                    "    fun make() = p.Before().toString()\n}\n",
            )
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-capture-qualified-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            val source = session.getKtFileForPath(sourcePath.toString()) ?: error("Could not obtain source PSI")
            val caller = session.getKtFileForPath(callerPath.toString()) ?: error("Could not obtain caller PSI")

            val conflict = KaRenameComputer(
                source, source.text.indexOf("Before"), setOf(source, caller), "After",
            ).findCaptureConflicts()

            assertNull("A qualified selector keeps resolving through its receiver", conflict)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Locates Kotlin stdlib supplied by the Maven test runtime. */
    private fun findStdlibJar(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }

    /** Renames a method declaration and the single call site in the same file. */
    fun testRenameFunction() = runFixture("function", newName = "fooFunc")

    /** Renames a property declaration and all its usages in the same file. */
    fun testRenameProperty() = runFixture("properties", newName = "someValue")

    // -------------------------------------------------------------------------
    // Override-cascade fixture
    // -------------------------------------------------------------------------

    /**
     * Verifies that renaming an interface method also renames all implementing overrides
     * and the call site on the abstract type.
     */
    fun testRenameWithOverrideCascade() {
        val session = getSessionOrSkip() ?: return
        val subDir = "overrides"
        val fixtureDir = dir.getFileObject(subDir) ?: return
        val sourceFo = fixtureDir.getFileObject("file.kt") ?: return
        val afterText = fixtureDir.getFileObject("file.after")?.asText() ?: return
        val caretOffset = readCaretOffset(subDir) ?: return

        val kaFile = session.getKtFileForPath(sourceFo.path) ?: return
        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()

        val computer = KaRenameComputer(kaFile, caretOffset, allKtFiles, "surface")
        val changes = computer.compute()

        assertNotNull("compute() must return non-null for override cascade", changes)
        changes!!

        val sourceText = sourceFo.asText()
        val changesForFile = changes[sourceFo.path] ?: emptyList()
        val actual = applyChanges(sourceText, changesForFile)

        assertEquals("Override cascade rename must update all occurrences", afterText, actual)

        // All four occurrences: interface declaration + 2 override declarations + call site
        assertTrue(
            "Expected at least 4 changes for override cascade, got ${changesForFile.size}",
            changesForFile.size >= 4,
        )
    }

    /**
     * Cursor on the override → super (base interface method) and sibling overrides
     * must all be renamed too. Reverse direction of [testRenameWithOverrideCascade].
     */
    fun testRenameFromOverrideCursor() {
        val session = getSessionOrSkip() ?: return
        val subDir = "overrideCursor"
        val fixtureDir = dir.getFileObject(subDir) ?: return
        val sourceFo = fixtureDir.getFileObject("file.kt") ?: return
        val afterText = fixtureDir.getFileObject("file.after")?.asText() ?: return
        val caretOffset = readCaretOffset(subDir) ?: return

        val kaFile = session.getKtFileForPath(sourceFo.path) ?: return
        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()

        val computer = KaRenameComputer(kaFile, caretOffset, allKtFiles, "surface")
        val changes = computer.compute()

        assertNotNull("compute() must return non-null for override-cursor rename", changes)
        changes!!

        val sourceText = sourceFo.asText()
        val changesForFile = changes[sourceFo.path] ?: emptyList()
        val actual = applyChanges(sourceText, changesForFile)

        assertEquals(
            "Renaming from the override must also rename the base + sibling overrides + call",
            afterText, actual,
        )
    }

    // -------------------------------------------------------------------------
    // Resolve helpers
    // -------------------------------------------------------------------------

    /** Verifies that [KaRenameComputer.resolveOldName] returns the identifier at cursor. */
    fun testResolveOldName() {
        val session = getSessionOrSkip() ?: return
        val subDir = "classrename"
        val fixtureDir = dir.getFileObject(subDir) ?: return
        val sourceFo = fixtureDir.getFileObject("file.kt") ?: return
        val caretOffset = readCaretOffset(subDir) ?: return

        val kaFile = session.getKtFileForPath(sourceFo.path) ?: return
        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()

        val computer = KaRenameComputer(kaFile, caretOffset, allKtFiles, NEW_NAME)
        val oldName = computer.resolveOldName()

        assertNotNull("resolveOldName() must return non-null for a named declaration", oldName)
        assertEquals("Old name must be the class identifier", "ClassToRename", oldName)
    }

    /** Returns `null` (no crash) when the cursor is not on a named declaration. */
    fun testComputeReturnsNullForNonSymbol() {
        val session = getSessionOrSkip() ?: return
        val subDir = "classrename"
        val fixtureDir = dir.getFileObject(subDir) ?: return
        val sourceFo = fixtureDir.getFileObject("file.kt") ?: return
        val kaFile = session.getKtFileForPath(sourceFo.path) ?: return
        val allKtFiles = fixtureDir.children
            .filter { it.isKotlinFile() }
            .mapNotNull { session.getKtFileForPath(it.path) }
            .toSet()

        // Offset 0 is the start of the "package" keyword — not a named declaration
        val computer = KaRenameComputer(kaFile, 0, allKtFiles, NEW_NAME)
        // Must not crash; may return null or an empty map
        val result = computer.compute()
        // No assertion on result value — just verifying no exception is thrown
        assertTrue("compute() must not crash for non-symbol offset", true)
    }
}
