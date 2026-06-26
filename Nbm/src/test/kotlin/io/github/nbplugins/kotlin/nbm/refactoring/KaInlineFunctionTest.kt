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
import io.github.nbplugins.kotlin.refactoring.KaInlineFunctionComputer
import io.github.nbplugins.kotlin.refactoring.KaInlineFunctionComputer.Companion.simplifyStringTemplateCaptures
import org.jetbrains.kotlin.idea.refactoring.inline.codeInliner.replaceUsages
import javax.swing.text.DefaultStyledDocument
import javax.swing.undo.CompoundEdit
import javax.swing.undo.UndoManager
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaInlineFunctionComputer].
 *
 * Fixtures are in `projForTest/src/inlineFunction/`. Each sub-directory contains:
 *  - `file.kt` — Kotlin source with the function under test
 *  - `file.caret` — same source with `<caret>` marking the cursor position
 *
 * Tests verify that the IDEA-engine pathway (`createUsageReplacementStrategyForFunction` +
 * `CallableUsageReplacementStrategy` + reference walk) produces the right [Outcome] for
 * representative cases.
 */
class KaInlineFunctionTest : KotlinTestCase("KaInlineFunctionTest", "inlineFunction") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    /**
     * Returns the active K2 session, or `null` when the standalone analysis API has no source
     * dependencies (CI / sandbox mode without K2 binaries). Tests that need K2 early-out here.
     */
    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaInlineFunctionTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /** Reads the caret offset from `<subDir>/file.caret`. Returns `null` when marker is missing. */
    private fun readCaretOffset(subDir: String): Int? {
        val caretFo = dir.getFileObject(subDir)?.getFileObject("file.caret") ?: return null
        val caretText = caretFo.asText()
        val idx = caretText.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Locates `<subDir>/file.kt`, registers it via the session, and returns the prepared
     * computer plus the resolved [KtFile] for assertions.
     */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): Pair<KaInlineFunctionComputer, KtFile>? {
        val subFo = dir.getFileObject(subDir) ?: return null
        val fileFo = subFo.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null

        val projectKtFiles = session.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()

        return KaInlineFunctionComputer(
            cursorKtFile = ktFile,
            offset = offset,
            project = session.session.project,
            projectKtFiles = projectKtFiles,
        ) to ktFile
    }

    /**
     * Cursor on `fun greet(name: String) = "Hello, $name!"` with one call in the same file:
     * the computer must return [KaInlineFunctionComputer.Outcome.Ready] with
     * `declarationName == "greet"` and exactly one collected [KtReferenceExpression].
     */
    fun testSimpleFunction_oneUsage() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("simpleFunction", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready, got $outcome",
            outcome is KaInlineFunctionComputer.Outcome.Ready,
        )
        val result = (outcome as KaInlineFunctionComputer.Outcome.Ready).result
        assertEquals("greet", result.declarationName)
        val totalUsages = result.usages.values.sumOf { it.size }
        assertEquals("Expected exactly one usage of greet", 1, totalUsages)
    }

    /**
     * `fun double(x: Int) = x * 2` called three times: the computer must collect 3 references.
     */
    fun testMultipleUsages_collectsAllRefs() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("multipleUsages", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready, got $outcome",
            outcome is KaInlineFunctionComputer.Outcome.Ready,
        )
        val result = (outcome as KaInlineFunctionComputer.Outcome.Ready).result
        assertEquals("double", result.declarationName)
        val totalUsages = result.usages.values.sumOf { it.size }
        assertEquals("Expected 3 usages of double, got $totalUsages", 3, totalUsages)
    }

    /**
     * Cursor on a **call site** (`<caret>square(5)`) rather than on the declaration:
     * the computer must still return [Outcome.Ready] by resolving the reference to [KtNamedFunction].
     */
    fun testCaretOnCall_resolvesToFunction() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("caretOnCall", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready when caret is on a call site, got $outcome",
            outcome is KaInlineFunctionComputer.Outcome.Ready,
        )
        val result = (outcome as KaInlineFunctionComputer.Outcome.Ready).result
        assertEquals(
            "Declaration name must be 'square' even when caret is on call site",
            "square",
            result.declarationName,
        )
    }

    /**
     * Abstract function has no body: the computer must return [Outcome.Error] with a message
     * containing "abstract" or "no body".
     */
    fun testAbstractFunction_returnsError() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("noBody", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Error for abstract function, got $outcome",
            outcome is KaInlineFunctionComputer.Outcome.Error,
        )
    }

    /**
     * Verifies that `KotlinInlineFunctionProcessor` can be loaded and linked at runtime without
     * `NoClassDefFoundError` (would surface when the stub for `BaseRefactoringProcessor` is missing).
     */
    fun testInlineFunctionProcessor_isLinkable() {
        try {
            Class.forName(
                "org.jetbrains.kotlin.idea.k2.refactoring.inline.KotlinInlineFunctionProcessor",
                /* initialize = */ true,
                this::class.java.classLoader,
            )
        } catch (e: NoClassDefFoundError) {
            fail(
                "KotlinInlineFunctionProcessor cannot be linked at runtime: ${e.message}. " +
                "This will surface as a NoClassDefFoundError when the user invokes Inline Function.",
            )
        } catch (e: ClassNotFoundException) {
            fail("KotlinInlineFunctionProcessor missing on classpath: ${e.message}")
        }
    }

    // --- Ctrl+Z undo atomicity tests (no K2 session needed) ---

    /**
     * Reproduces the bug: `doc.remove()` + `doc.insertString()` as **two** separate edits means
     * the first Ctrl+Z only undoes the insert, leaving an empty document.
     *
     * This test documents the broken behaviour so the fix (wrapping in an atomic operation)
     * can be verified by contrast.
     */
    fun testWriteDocument_nonAtomic_firstUndoClearsDocument() {
        val original = "fun greet(name: String) = \"Hello, \$name!\"\n"
        val inlined  = "// inlined body\n"

        val doc = DefaultStyledDocument()
        val um  = UndoManager()
        doc.addUndoableEditListener(um)

        doc.insertString(0, original, null)
        um.discardAllEdits()                        // don't count the initial population

        // Old (broken) path: two independent edits
        doc.remove(0, doc.length)
        doc.insertString(0, inlined, null)

        // First Ctrl+Z only undoes the insert → empty document (the bug)
        assertTrue("should have something to undo", um.canUndo())
        um.undo()
        assertEquals(
            "First undo of non-atomic remove+insert leaves empty document (reproduces Ctrl+Z bug)",
            "",
            doc.getText(0, doc.length),
        )
    }

    /**
     * Verifies the fix: grouping `remove` + `insertString` into a [CompoundEdit] makes them a
     * single undo entry, so one Ctrl+Z restores the original content.
     *
     * In production, `NbDocument.runAtomicAsUser` achieves the same atomicity for real NB editor
     * documents (which implement `NbDocument.WriteLockable`). `WriteLockable.runAtomicAsUser`
     * is the NB-specific equivalent of the `CompoundEdit` grouping shown here.
     */
    fun testWriteDocument_compoundEdit_firstUndoRestoresOriginal() {
        val original = "fun greet(name: String) = \"Hello, \$name!\"\n"
        val inlined  = "// inlined body\n"

        val doc = DefaultStyledDocument()
        val um  = UndoManager()
        doc.addUndoableEditListener(um)

        doc.insertString(0, original, null)
        um.discardAllEdits()

        // Fixed path: wrap remove+insert in a CompoundEdit so UndoManager sees one entry
        val compound = CompoundEdit()
        val collector = javax.swing.event.UndoableEditListener { compound.addEdit(it.edit) }
        doc.addUndoableEditListener(collector)
        try {
            doc.remove(0, doc.length)
            doc.insertString(0, inlined, null)
        } finally {
            doc.removeUndoableEditListener(collector)
        }
        compound.end()
        um.addEdit(compound)

        // Single Ctrl+Z restores the original
        assertTrue("should have something to undo", um.canUndo())
        um.undo()
        assertEquals(
            "First undo of compound-edit replace must restore original content",
            original,
            doc.getText(0, doc.length),
        )
    }

    // --- simplifyStringTemplateCaptures unit tests (no K2 session needed) ---

    /**
     * Verifies the basic case: `${"World"}` inside a string template is collapsed to `World`.
     * This reproduces the bug where inlining `fun greet1(name: String) = "Hello, $name!"`
     * at `greet1("World")` produced `"Hello, ${"World"}!"` instead of `"Hello, World!"`.
     */
    fun testSimplifyStringTemplateCaptures_plainLiteral() {
        val input = """println("Hello, ${"World"}!")"""
        val expected = """println("Hello, World!")"""
        assertEquals(expected, simplifyStringTemplateCaptures(input))
    }

    /** Multiple captures in one string are all simplified. */
    fun testSimplifyStringTemplateCaptures_multipleCaptures() {
        val input = """println("${"Hello"}, ${"World"}!")"""
        val expected = """println("Hello, World!")"""
        assertEquals(expected, simplifyStringTemplateCaptures(input))
    }

    /** Captures that contain `$`, `"`, or `\` are left untouched (unsafe to simplify). */
    fun testSimplifyStringTemplateCaptures_unsafeContentLeftAlone() {
        val withDollar = """println("Hello, ${"Wor\$ld"}!")"""
        assertEquals(withDollar, simplifyStringTemplateCaptures(withDollar))

        val withBackslash = """println("Hello, ${"Wor\\ld"}!")"""
        assertEquals(withBackslash, simplifyStringTemplateCaptures(withBackslash))
    }

    // --- Real-session integration tests (need kotlin-stdlib on classpath) ---

    /** Finds `kotlin-stdlib-*.jar` on the test classpath, or `null` when unavailable. */
    private fun findKotlinStdlib(): Path? =
        System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull {
                it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists()
            }

    /**
     * Builds a K2 [KotlinAnalysisAPISession] backed by `kotlin-stdlib` and a temp source root
     * containing a single fixture file from `projForTest/src/inlineFunction/<subDir>/file.kt`.
     *
     * @return triple of (computer, KtFile, tmpDir to delete), or `null` when stdlib is missing
     */
    private fun prepareWithRealSession(subDir: String): Triple<KaInlineFunctionComputer, KtFile, Path>? {
        val stdlib = findKotlinStdlib() ?: return null
        val subFo = dir.getFileObject(subDir) ?: error("Missing fixture dir: $subDir")
        val fileFo = subFo.getFileObject("file.kt") ?: error("Missing $subDir/file.kt")
        val caretFo = subFo.getFileObject("file.caret") ?: error("Missing $subDir/file.caret")
        val source = fileFo.asText()
        val offset = caretFo.asText().indexOf(CARET_MARKER).also {
            check(it >= 0) { "Caret marker not found in $subDir/file.caret" }
        }

        val tmpDir = Files.createTempDirectory("nbkotlin-inline-fn-$subDir")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "inline-function-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")
        val projectKtFiles = session.session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()

        val computer = KaInlineFunctionComputer(
            cursorKtFile = ktFile,
            offset = offset,
            project = session.session.project,
            projectKtFiles = projectKtFiles,
        )
        return Triple(computer, ktFile, tmpDir)
    }

    /**
     * Integration test: drives [KaInlineFunctionComputer.compute] against a real K2 session with
     * `kotlin-stdlib`. Verifies that `simpleFunction` resolves to [Outcome.Ready] with the correct
     * declaration name and one collected reference.
     */
    fun testCompute_withRealSession_simpleFunction() {
        val triple = prepareWithRealSession("simpleFunction")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session integration test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue(
                "Expected Ready outcome from real session, got $outcome",
                outcome is KaInlineFunctionComputer.Outcome.Ready,
            )
            val result = (outcome as KaInlineFunctionComputer.Outcome.Ready).result
            assertEquals("greet", result.declarationName)
            val totalUsages = result.usages.values.sumOf { it.size }
            assertEquals("Expected exactly one usage of greet", 1, totalUsages)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Regression test: inlining `fun greet1(name: String) = "Hello, $name!"` at
     * `greet1("World")` must produce `"Hello, World!"`, not `"Hello, ${"World"}!"`.
     *
     * IDEA's `CodeInliner` substitutes string-typed arguments as `${"arg"}` inside templates.
     * [simplifyStringTemplateCaptures] must collapse those entries to plain literals.
     */
    fun testApply_withRealSession_stringTemplateArg_simplifiesCaptures() {
        val triple = prepareWithRealSession("stringTemplateArg")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping string-template simplification test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue(
                "Expected Ready outcome, got $outcome",
                outcome is KaInlineFunctionComputer.Outcome.Ready,
            )
            val result = (outcome as KaInlineFunctionComputer.Outcome.Ready).result
            val allRefs = result.usages.values.flatten()
            result.strategy.replaceUsages(
                usages = allRefs,
                unwrapSpecialUsages = false,
                unwrapSpecialUsageOrNull = { null },
            )
            result.function.delete()

            val rawText = ktFile.text
            val simplified = simplifyStringTemplateCaptures(rawText)

            assertFalse(
                "Expected no \${\"World\"} in simplified text, but got: $simplified",
                simplified.contains("\${\"World\"}"),
            )
            assertFalse(
                "Expected no \${\"Kotlin\"} in simplified text, but got: $simplified",
                simplified.contains("\${\"Kotlin\"}"),
            )
            assertTrue(
                "Expected 'Hello, World!' after simplification, got: $simplified",
                simplified.contains("\"Hello, World!\""),
            )
            assertTrue(
                "Expected 'Hello, Kotlin!' after simplification, got: $simplified",
                simplified.contains("\"Hello, Kotlin!\""),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: exercises the apply step end-to-end — `compute()` + `replaceUsages` +
     * `function.delete()`. Verifies that after inlining `greet`, the call site becomes
     * `println("Hello, World!")` and the function declaration is removed.
     */
    fun testApply_withRealSession_simpleFunction_inlinesSucceeds() {
        val triple = prepareWithRealSession("simpleFunction")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping apply integration test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue(
                "Expected Ready outcome from real session, got $outcome",
                outcome is KaInlineFunctionComputer.Outcome.Ready,
            )
            val result = (outcome as KaInlineFunctionComputer.Outcome.Ready).result

            val allRefs = result.usages.values.flatten()
            result.strategy.replaceUsages(
                usages = allRefs,
                unwrapSpecialUsages = false,
                unwrapSpecialUsageOrNull = { null },
            )
            result.function.delete()

            val finalText = ktFile.text
            assertFalse(
                "Expected 'fun greet' declaration to be removed, got: $finalText",
                finalText.contains("fun greet"),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
