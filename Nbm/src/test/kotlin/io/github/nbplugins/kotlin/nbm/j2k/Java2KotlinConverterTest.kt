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
import org.jetbrains.kotlin.idea.references.mainReference
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
 * `.after` files, which were produced by the old K1 converter; the structural content (modifiers,
 * `object` vs `class`, property syntax, imports) must match exactly.
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

    /** Annotations on the class and on a method survive the conversion. */
    fun testWithAnnotations() {
        val actual = convert("annotated") ?: return
        println("=== annotated.kt ===\n$actual\n=== end annotated.kt ===")
        assertTrue("class annotation lost: $actual", actual.contains("JsonNaming"))
        assertTrue("method annotation lost: $actual", actual.contains("JsonIgnore"))
    }

    /** A getter/setter pair becomes a Kotlin property. */
    fun testProperties() {
        val actual = convert("properties") ?: return
        println("=== properties.kt ===\n$actual\n=== end properties.kt ===")
        assertTrue("property not formed: $actual", actual.contains("var name"))
    }

    /** The same shapes without annotations isolate the annotation handling. */
    fun testAbstractWithoutAnnotations() {
        val actual = convert("abstractNoAnn") ?: return
        println("=== abstractNoAnn.kt ===\n$actual\n=== end abstractNoAnn.kt ===")
        assertTrue("property not formed: $actual", actual.contains("primaryKey"))
    }

    /** Two properties where one has no setter. */
    fun testTwoGetters() {
        val actual = convert("twoGetters") ?: return
        println("=== twoGetters.kt ===\n$actual\n=== end twoGetters.kt ===")
        assertTrue("property not formed: $actual", actual.contains("ballast"))
    }

    /** Diagnostic: does the produced setter of `properties.kt` count as redundant when re-parsed? */
    fun testDiagnoseRedundantSetter() {
        val actual = convert("properties") ?: return
        val stdlib = findStdlibJarOrNull() ?: return
        val kotlinFileObject = FileUtil.createData(dir, "diagnose.kt")
        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = project.projectDirectory.name,
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(FileUtil.toFile(dir).toPath()),
        )
        try {
            session.freezeFileContent(kotlinFileObject.path)
            try {
                session.updateFrozenFileContent(kotlinFileObject.path, actual)
                val ktFile = session.getKtFileForPath(kotlinFileObject.path)!!
                for (klass in ktFile.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtClass>()) {
                    for (property in klass.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>()) {
                        for (accessor in listOfNotNull(property.getter, property.setter)) {
                            val body = accessor.bodyExpression
                            val statements = (body as? org.jetbrains.kotlin.psi.KtBlockExpression)?.statements
                            val right = (statements?.singleOrNull() as? org.jetbrains.kotlin.psi.KtBinaryExpression)?.right
                            val parameter = accessor.parameterList?.parameters?.singleOrNull()
                            val redundant = statements?.size == 1 &&
                                right?.mainReference?.resolve() == parameter
                            println(
                                "DIAG property=${property.name} accessor=${accessor.text.replace("\n", "\\n")} " +
                                    "isSetter=${accessor.isSetter} statements=${statements?.size} " +
                                    "right=${right?.text} rightRef=${right?.mainReference} " +
                                    "resolved=${right?.mainReference?.resolve()} " +
                                    "param=$parameter " +
                                    "redundant=$redundant",
                            )
                        }
                    }
                }
            } finally {
                runCatching { session.unfreezeFileContent(kotlinFileObject.path) }
            }
        } finally {
            runCatching { kotlinFileObject.delete() }
            KotlinAnalysisAPISession.invalidate(project)
        }
    }

    /** The import block is inserted after the package directive, sorted. */
    fun testWithImportsAfterPackage() {
        val text = "package a.b\n\nclass C\n"
        val actual = Java2KotlinConverter.withImports(text, setOf(FqName("z.Z"), FqName("a.A")))
        assertEquals("package a.b\n\nimport a.A\nimport z.Z\n\nclass C\n", actual)
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
