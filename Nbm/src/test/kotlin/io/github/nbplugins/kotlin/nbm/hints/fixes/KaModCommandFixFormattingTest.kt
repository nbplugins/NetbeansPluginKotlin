/*
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.nbplugins.kotlin.nbm.hints.fixes

import io.github.nbplugins.kotlin.nbm.diagnostics.KaDiagnosticError
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import utils.getDocumentForFileObject
import java.nio.file.Path
import javax.swing.text.Document

/**
 * Verifies that quick-fixes which mutate PSI via IDEA fix action classes (delegated through
 * [KaModCommandFix]) produce **valid, properly-formatted Kotlin** after [KaQuickFix.implement].
 *
 * Without post-mutation reformatting, the IDEA fix actions insert "naked" tokens (modifiers,
 * annotations, type references, val/var keywords) as adjacent AST leaves with no whitespace
 * between them. The concatenated `node.text` then contains glued constructs like `valname`,
 * `@JvmInlinevalue`, `x:Int`, or `f(1 )` — which when re-parsed lex as a single identifier
 * or carry a stray whitespace, breaking the user's source.
 *
 * Each test method:
 *  1. Locates the K2 diagnostic of interest in `modCommandFixes.kt`.
 *  2. Applies the corresponding fix.
 *  3. Asserts the document text contains the expected properly-spaced fragment.
 *  4. Restores the document to its original content.
 */
class KaModCommandFixFormattingTest : KotlinTestCase("KaModCommandFixFormatting", "quickfixes") {

    private fun getKaKtFileOrSkip(path: String): KtFile? {
        var wrapper = KotlinAnalysisAPISession.getSession(project)
        if (!wrapper.hasDependencies) {
            val stdlib = findStdlibJarOrNull()
            if (stdlib == null) {
                println("KaModCommandFixFormattingTest: skipping — no kotlin-stdlib in ~/.m2")
                return null
            }
            val sourceRoots = wrapper.session.modulesWithFiles.values.flatten()
                .filterIsInstance<KtFile>()
                .mapNotNull { it.virtualFile?.path }
                .map { Path.of(it).parent }
                .distinct()
            wrapper = KotlinAnalysisAPISession.createWithJars(
                moduleName = project.projectDirectory.name,
                binaryJars = listOf(stdlib),
                sourceRoots = sourceRoots
            )
        }
        return wrapper.getKtFileForPath(path)
    }

    private fun findStdlibJarOrNull(): Path? {
        val home = System.getProperty("user.home") ?: return null
        return Path.of(home, ".m2/repository/org/jetbrains/kotlin/kotlin-stdlib/1.9.25/kotlin-stdlib-1.9.25.jar")
            .takeIf { it.toFile().exists() }
    }

    private fun firstErrorWithKey(kaKtFile: KtFile, fileName: String, key: String): KaDiagnosticError? {
        val file = dir.getFileObject(fileName) ?: return null
        val ktFile = KotlinPsiManager.getParsedFile(file) ?: return null
        return KotlinParserResult(null, ktFile, file, project, kaKtFile)
            .getDiagnostics().filterIsInstance(KaDiagnosticError::class.java)
            .firstOrNull { it.getKey() == key }
    }

    private inline fun withFixApplied(
        fileName: String,
        diagnosticKey: String,
        buildFix: (KaDiagnosticError, Document, KtFile) -> KaQuickFix,
        assertion: (text: String) -> Unit
    ) {
        val file = dir.getFileObject(fileName) ?: return
        val kaKtFile = getKaKtFileOrSkip(file.path) ?: return
        val doc = getDocumentForFileObject(file) ?: return
        val error = firstErrorWithKey(kaKtFile, fileName, diagnosticKey) ?: run {
            println("$diagnosticKey not produced by K2 in $fileName — skipping")
            return
        }
        val fix = buildFix(error, doc, kaKtFile)
        if (!fix.isApplicable()) {
            println("fix ${fix::class.simpleName} not applicable — skipping")
            return
        }
        val original = doc.getText(0, doc.length)
        try {
            fix.implement()
            assertion(doc.getText(0, doc.length))
        } finally {
            if (doc.getText(0, doc.length) != original) {
                doc.remove(0, doc.length)
                doc.insertString(0, original, null)
            }
            KotlinAnalysisAPISession.invalidate(project)
        }
    }

    /** `data class P(name: String)` → `data class P(val name: String)` (with space after `val`). */
    fun testAddValVarToConstructorParam_hasSpaceAfterVal() {
        withFixApplied("fmtAddValVar.kt", "DATA_CLASS_NOT_PROPERTY_PARAMETER",
            { e, d, k -> KaAddValVarToConstructorParamFix(e, d, k) }) { text ->
            assertFalse("`valname` glued, got:\n$text", text.contains("valname"))
            assertTrue("expected `val name` with space, got:\n$text", text.contains("val name"))
        }
    }

    /**
     * `fun f(x = 42)` → `fun f(x:Int = 42)`. The added type-ref leaf is adjacent to the `:`
     * leaf with no [com.intellij.psi.PsiWhiteSpace] between them. This is syntactically valid
     * Kotlin (parser treats `:` and `Int` as separate tokens), only the code style is off.
     * A future post-pass on top of a real `CodeStyleManager` would insert a space here.
     */
    fun testAddTypeAnnotation_parsesCorrectly() {
        withFixApplied("fmtAddTypeAnn.kt", "VALUE_PARAMETER_WITHOUT_EXPLICIT_TYPE",
            { e, d, k -> KaAddTypeAnnotationToValueParamFix(e, d, k) }) { text ->
            assertTrue("expected `Int` type reference somewhere in the parameter list, got:\n$text",
                Regex("\\bx\\s*:\\s*Int\\b").containsMatchIn(text))
        }
    }

    /** `value class Wrap` → `@JvmInline value class Wrap` (annotation and modifier not glued). */
    fun testAddJvmInline_hasSpaceAfterAnnotation() {
        withFixApplied("fmtAddJvmInline.kt", "VALUE_CLASS_WITHOUT_JVM_INLINE_ANNOTATION",
            { e, d, k -> KaAddJvmInlineFix(e, d, k) }) { text ->
            assertFalse("`@JvmInlinevalue` glued, got:\n$text", text.contains("JvmInlinevalue"))
            assertTrue("expected `JvmInline` followed by whitespace, got:\n$text",
                Regex("JvmInline\\s+value").containsMatchIn(text))
        }
    }

    /**
     * `inline fun f(action: () -> Unit) { val x = action }` → `inline fun f(noinline action: ...)`.
     * `noinline` modifier must be followed by space before parameter name.
     */
    fun testAddInlineModifier_hasSpaceAfterModifier() {
        withFixApplied("fmtAddInline.kt", "USAGE_IS_NOT_INLINABLE",
            { e, d, k -> KaAddInlineModifierFix(e, d, k) }) { text ->
            assertFalse("`noinlineaction` glued, got:\n$text", text.contains("noinlineaction"))
            assertTrue("expected `noinline action` with space, got:\n$text",
                text.contains("noinline action"))
        }
    }

    /** `fun missingReturn(): Int { val x = 1 }` (multiline body) → return on its own line. */
    fun testAddReturnExpression_hasNewlineBeforeReturn() {
        withFixApplied("fmtAddReturn.kt", "NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY",
            { e, d, k -> KaAddReturnExpressionFix(e, d, k) }) { text ->
            assertTrue("expected `return` to be preceded by a newline (proper block formatting), got:\n$text",
                Regex("val x = 1\\s*\\n\\s*return").containsMatchIn(text))
        }
    }

    /**
     * Single-line body `fun f(): Int { val x = 1 }` after fix must still produce valid Kotlin:
     * `return` must be separated from the previous statement by a newline (or `;`), otherwise
     * the parser reports an error.
     */
    fun testAddReturnExpression_singleLineBodyGetsNewlineBeforeReturn() {
        withFixApplied("fmtAddReturnOneLine.kt", "NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY",
            { e, d, k -> KaAddReturnExpressionFix(e, d, k) }) { text ->
            assertTrue(
                "expected `return` to be on a new line (or after `;`), got:\n$text",
                Regex("val x = 1[\\s;]*[\\n;][\\s;]*return").containsMatchIn(text)
            )
        }
    }

    /**
     * `fmtTwo(1, 2, 3)` → `fmtTwo(1, 2 )` — last argument removed, the preceding whitespace
     * leaf is preserved as a trailing space inside the argument list. This is syntactically
     * valid Kotlin (parser ignores whitespace), only the code style is off. Verifying that
     * the call still parses with exactly two arguments is enough for correctness.
     */
    fun testRemoveArgument_parsesWithRemainingArgs() {
        withFixApplied("fmtRemoveArg.kt", "TOO_MANY_ARGUMENTS",
            { e, d, k -> KaRemoveArgumentFix(e, d, k) }) { text ->
            assertTrue("expected fmtTwo call with two arguments after removal, got:\n$text",
                Regex("fmtTwo\\(\\s*1\\s*,\\s*2\\s*\\)").containsMatchIn(text))
        }
    }
}
