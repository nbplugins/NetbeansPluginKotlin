/*
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
 */
package io.github.nbplugins.kotlin.nbm.j2k

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import java.nio.file.Path
import org.jetbrains.kotlin.name.FqName
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import utils.KotlinTestCase

/**
 * Tests the Java → Kotlin conversion adapter ([Java2KotlinConverter]).
 *
 * The end-to-end cases run the real IDEA K2 J2K engine over the fixtures in
 * `projForTest/src/j2k` and compare the result with the `.after` companion file. The comparison
 * is whitespace-normalised because the K2 formatter's output need not be byte-identical to the
 * checked-in `.after` files; the structural content (modifiers, `object` vs `class`, property
 * syntax, imports) must match exactly.
 */
class Java2KotlinConverterTest : KotlinTestCase("J2K converter test", "j2k") {

    /**
     * Converts `<name>.java` and asserts the result equals `<name>.after` (normalised).
     *
     * Skips the test when `kotlin-stdlib` is missing from the local Maven repository: without it
     * the K2 post-processing cannot analyse the converted file, and the comparison would test
     * nothing but the unprocessed printer output.
     *
     * @param name fixture base name (without extension)
     */
    private fun doTest(name: String) {
        val actual = convert(name) ?: return
        val expected = dir.getFileObject("$name.after").asText()
        assertEquals(
            "converted text for $name.java",
            normalize(expected),
            normalize(actual),
        )
    }

    /**
     * Converts fixture `<name>.java` and returns the produced Kotlin text.
     *
     * Skips the test when `kotlin-stdlib` is missing from the local Maven repository: without it
     * the K2 post-processing cannot analyse the converted file, and the comparison would test
     * nothing but the unprocessed printer output.
     *
     * @param name fixture base name (without extension)
     * @return the converted Kotlin text, or `null` when the fixture was skipped
     */
    private fun convert(name: String): String? {
        val stdlib = findStdlibJarOrNull()
        if (stdlib == null) {
            println("${javaClass.simpleName}: skipping — kotlin-stdlib not found in ~/.m2")
            return null
        }
        val javaFileObject = dir.getFileObject("$name.java")
        assertNotNull("fixture $name.java", javaFileObject)
        val kotlinFileObject = FileUtil.createData(dir, "$name.kt")
        try {
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = project.projectDirectory.name,
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(FileUtil.toFile(dir).toPath()),
            )
            val actual = Java2KotlinConverter.convertInSession(
                session, project, javaFileObject, kotlinFileObject,
            )
            assertNotNull("conversion of $name.java produced no text", actual)
            return actual!!
        } finally {
            kotlinFileObject.delete()
            KotlinAnalysisAPISession.invalidate(project)
        }
    }

    /** Converts a class with one instance method. */
    fun testSimpleCase() = doTest("simple")

    /** Converts a class whose methods are all static — the class must become an `object`. */
    fun testWithStaticMethods() = doTest("withStaticMethod")

    /** Converts a class mixing instance and static methods — statics go to a `companion object`. */
    fun testMixed() = doTest("mixed")

    /** Converts a class with an inner class. */
    fun testWithInnerClass() = doTest("withInnerClass")

    /** Converts an interface. */
    fun testInterface() = doTest("interface")

    /**
     * Annotations on the class survive, and an annotation on a getter moves to the property's
     * `@get:` use site.
     */
    fun testWithAnnotations() = doTest("annotated")

    /** A getter/setter pair becomes a Kotlin property. */
    fun testProperties() = doTest("properties")

    /** An abstract getter becomes an abstract `val`, without annotations in the way. */
    fun testAbstractWithoutAnnotations() = doTest("abstractNoAnn")

    /**
     * A getter whose name differs from the backing field renames the merged property, and the
     * accessors of the field collapse into it. The duplicate setter left behind by a stale
     * analysis would show up as a dangling `set` block in the result.
     */
    fun testTwoGetters() = doTest("twoGetters")

    /** The import block is inserted after the package directive, sorted. */
    fun testWithImportsAfterPackage() {
        val text = "package a.b\n\nclass C\n"
        val actual = Java2KotlinConverter.withImports(text, setOf(FqName("z.Z"), FqName("a.A")))
        assertEquals("package a.b\n\nimport a.A\nimport z.Z\n\nclass C\n", actual)
    }

    /**
     * A header comment before the package directive must not hide it.
     *
     * The engine keeps the license header above the package; treating the directive as absent
     * would prepend the imports above the header and produce invalid Kotlin.
     */
    fun testWithImportsAfterHeaderComment() {
        val text = "/*\n * License\n */\npackage a.b\n\nclass C\n"
        val actual = Java2KotlinConverter.withImports(text, setOf(FqName("a.A")))
        assertEquals("/*\n * License\n */\npackage a.b\n\nimport a.A\n\nclass C\n", actual)
    }

    /** A file whose last line is the package directive still gets the imports after it. */
    fun testWithImportsAfterPackageWithoutTrailingNewline() {
        val text = "package a.b"
        val actual = Java2KotlinConverter.withImports(text, setOf(FqName("a.A")))
        assertEquals("package a.b\n\nimport a.A\n\n", actual)
    }

    /** Without a package directive the import block goes to the very top. */
    fun testWithImportsWithoutPackage() {
        val text = "class C\n"
        val actual = Java2KotlinConverter.withImports(text, setOf(FqName("a.A")))
        assertEquals("import a.A\n\nclass C\n", actual)
    }

    /** No imports means the text is returned unchanged. */
    fun testWithImportsEmpty() {
        val text = "package a.b\n\nclass C\n"
        assertEquals(text, Java2KotlinConverter.withImports(text, emptySet()))
    }

    /**
     * The final formatting pass separates braces that the K2 printer glued together.
     *
     * The engine's own `FormatCodeProcessing` is a no-op in a standalone session (it goes through
     * the mock `CodeStyleManager`), so [Java2KotlinConverter.formatConvertedText] formats through
     * the plugin's text-based formatter instead.
     */
    fun testFormatConvertedTextSeparatesGluedBraces() {
        val text = "package a\n\nclass A {\n    fun f() {\n    }}"
        val formatted = Java2KotlinConverter.formatConvertedText(text, "A.kt", project)
        assertFalse("closing braces must be on separate lines: $formatted", formatted.contains("}}"))
    }

    /** An existing Kotlin file is never overwritten: the conversion refuses and leaves it intact. */
    fun testRefusesWhenKotlinFileExists() {
        val javaFileObject = dir.getFileObject("simple.java")
        val existing = FileUtil.createData(dir, "simple.kt")
        try {
            existing.setAttribute("sentinel", true)
            Java2KotlinConverter.convertNow(project, javaFileObject)
            assertEquals(0, existing.getSize())
            assertNotNull("the Java file must not be deleted", dir.getFileObject("simple.java"))
        } finally {
            existing.delete()
        }
    }

    /**
     * Collapses formatting differences: trims each line, drops blank lines and multiple spaces.
     *
     * @param text the source text to normalise
     * @return the text with all formatting whitespace removed
     */
    private fun normalize(text: String): String =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("[ \t]+"), " ")

    /**
     * Returns the Kotlin standard library matching the bundled compiler, if present.
     *
     * @return the JAR path, or `null` when no matching stdlib exists in `~/.m2`
     */
    private fun findStdlibJarOrNull(): Path? {
        val home = System.getProperty("user.home") ?: return null
        return STDLIB_VERSIONS.asSequence()
            .map { Path.of(home, ".m2/repository/org/jetbrains/kotlin/kotlin-stdlib/$it/kotlin-stdlib-$it.jar") }
            .firstOrNull { it.toFile().isFile }
    }

    private companion object {
        /** Stdlib versions to try, newest first; the first must match `kotlin.runtime.version`. */
        val STDLIB_VERSIONS = listOf("2.3.21", "2.2.21", "2.2.20", "2.1.21", "2.0.21")
    }
}
