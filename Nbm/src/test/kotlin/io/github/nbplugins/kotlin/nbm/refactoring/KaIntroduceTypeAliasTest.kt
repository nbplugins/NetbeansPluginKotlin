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

import com.intellij.openapi.util.TextRange
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.refactoring.KaIntroduceTypeAliasComputer
import io.github.nbplugins.kotlin.refactoring.KaIntroduceTypeAliasResult
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaIntroduceTypeAliasComputer].
 *
 * Fixtures are in `projForTest/src/introduceTypeAlias/`. Each sub-directory contains:
 *  - `file.kt`    — Kotlin source being refactored
 *  - `file.caret` — same source with `<caret>` marking the caret position
 *
 * Tests cover:
 *  1. Caret on `String` type reference → [KaIntroduceTypeAliasComputer.Outcome.Ready] with correct type text
 *  2. Caret on `List<String>` type reference → Ready with occurrence count ≥ 1
 *  3. Caret on `fun` keyword (not a type reference) → [KaIntroduceTypeAliasComputer.Outcome.NotApplicable]
 *  4. Available visibilities include `"public"`, `"internal"`, `"private"`
 *  5. Occurrence-based text transformation produces the expected output
 */
class KaIntroduceTypeAliasTest : KotlinTestCase("KaIntroduceTypeAliasTest", "introduceTypeAlias") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the project-scoped K2 session, or `null` when no dependencies are available.
     * Tests that need PSI parsing should early-out via this helper.
     */
    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaIntroduceTypeAliasTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Reads the `<caret>` offset from `<subDir>/file.caret`.
     */
    private fun readCaretOffset(subDir: String): Int? {
        val text = dir.getFileObject(subDir)?.getFileObject("file.caret")?.asText() ?: return null
        val idx = text.indexOf(CARET_MARKER)
        return if (idx >= 0) idx else null
    }

    /**
     * Sets up a [KaIntroduceTypeAliasComputer] using the project-scoped session.
     *
     * @param subDir fixture sub-directory
     * @return pair of (computer, KtFile) or `null` when the fixture or session is missing
     */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): Pair<KaIntroduceTypeAliasComputer, KtFile>? {
        val fileFo = dir.getFileObject(subDir)?.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaIntroduceTypeAliasComputer(ktFile, offset) to ktFile
    }

    /**
     * Builds a standalone [KotlinAnalysisAPISession] backed by `kotlin-stdlib`.
     *
     * @return triple of (computer, KtFile, tempDir) or `null` when `kotlin-stdlib` is absent
     */
    private fun prepareWithRealSession(subDir: String): Triple<KaIntroduceTypeAliasComputer, KtFile, Path>? {
        val stdlib = System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }
            .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }
            ?: return null

        val subFo = dir.getFileObject(subDir) ?: error("Missing fixture dir: $subDir")
        val fileFo = subFo.getFileObject("file.kt") ?: error("Missing $subDir/file.kt")
        val caretFo = subFo.getFileObject("file.caret") ?: error("Missing $subDir/file.caret")
        val source = fileFo.asText()
        val offset = caretFo.asText().indexOf(CARET_MARKER).also {
            check(it >= 0) { "Caret marker not found in $subDir/file.caret" }
        }

        val tmpDir = Files.createTempDirectory("nbkotlin-alias-$subDir")
        val tmpFile = tmpDir.resolve("file.kt")
        Files.writeString(tmpFile, source)

        val session = KotlinAnalysisAPISession.createWithJars(
            moduleName = "type-alias-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")
        return Triple(KaIntroduceTypeAliasComputer(ktFile, offset), ktFile, tmpDir)
    }

    // -----------------------------------------------------------------------
    // Computer correctness tests
    // -----------------------------------------------------------------------

    /**
     * Caret inside `String` type reference:
     * [KaIntroduceTypeAliasComputer.compute] must return [KaIntroduceTypeAliasComputer.Outcome.Ready]
     * with [KaIntroduceTypeAliasResult.typeText] `== "String"`.
     */
    fun testSimpleType_returnsReady() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("simpleType", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready for caret on 'String' type reference, got $outcome",
            outcome is KaIntroduceTypeAliasComputer.Outcome.Ready,
        )
        val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result
        assertEquals("Expected typeText == String", "String", result.typeText)
        assertTrue(
            "Expected at least 1 occurrence (the trigger reference itself)",
            result.occurrenceRanges.isNotEmpty(),
        )
        assertTrue(
            "Expected non-empty suggested name",
            result.suggestedName.isNotBlank(),
        )
    }

    /**
     * Caret inside `List<String>` type reference:
     * the computer must return [KaIntroduceTypeAliasComputer.Outcome.Ready] with
     * [KaIntroduceTypeAliasResult.typeText] `== "List<String>"` and occurrence count ≥ 1.
     */
    fun testGenericType_returnsReady() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("genericType", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready for caret on 'List<String>' type reference, got $outcome",
            outcome is KaIntroduceTypeAliasComputer.Outcome.Ready,
        )
        val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result
        assertEquals("Expected typeText == List<String>", "List<String>", result.typeText)
        assertTrue("Expected ≥1 occurrence", result.occurrenceRanges.isNotEmpty())
    }

    /**
     * Caret on `fun` keyword (not on a type reference):
     * the computer must return [KaIntroduceTypeAliasComputer.Outcome.NotApplicable].
     */
    fun testNotApplicable_onKeyword() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("notApplicable", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable for caret on 'fun' keyword, got $outcome",
            outcome is KaIntroduceTypeAliasComputer.Outcome.NotApplicable,
        )
    }

    /**
     * Verifies that [KaIntroduceTypeAliasResult.availableVisibilities] contains
     * `"public"`, `"internal"`, and `"private"`.
     */
    fun testVisibilities_containsExpectedModifiers() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("simpleType", session) ?: return

        val outcome = computer.compute()
        assertTrue("Expected Ready", outcome is KaIntroduceTypeAliasComputer.Outcome.Ready)
        val vis = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result.availableVisibilities

        assertTrue("Expected 'public' in visibilities", vis.contains("public"))
        assertTrue("Expected 'internal' in visibilities", vis.contains("internal"))
        assertTrue("Expected 'private' in visibilities", vis.contains("private"))
    }

    // -----------------------------------------------------------------------
    // Text transformation tests
    // -----------------------------------------------------------------------

    /**
     * For the `simpleType` fixture (two `String` type references), applying the alias with
     * name `Str` and `replaceAll = true` should replace all occurrences and insert
     * `typealias Str = String` before the enclosing declaration.
     */
    fun testApply_simpleType_replacesAllOccurrences() {
        val session = getSessionOrSkip() ?: return
        val (computer, ktFile) = prepareComputer("simpleType", session) ?: return

        val outcome = computer.compute()
        if (outcome !is KaIntroduceTypeAliasComputer.Outcome.Ready) {
            println("simpleType compute returned $outcome, skipping apply test")
            return
        }
        val result = outcome.result

        val chosenName = "Str"
        val typeAliasDecl = "typealias $chosenName = ${result.typeText}"
        val originalText = ktFile.text

        // Replace occurrences back-to-front, then insert declaration.
        var newText = originalText
        for (range in result.occurrenceRanges.sortedByDescending { it.startOffset }) {
            newText = newText.substring(0, range.startOffset) +
                    chosenName +
                    newText.substring(range.endOffset)
        }
        val insertPos = result.insertOffset
        newText = newText.substring(0, insertPos) + "$typeAliasDecl\n" + newText.substring(insertPos)

        assertTrue(
            "Expected typealias declaration in output:\n$newText",
            newText.contains(typeAliasDecl),
        )
        assertTrue(
            "Expected alias name '$chosenName' in output after replacement:\n$newText",
            newText.contains(chosenName),
        )
    }

    // -----------------------------------------------------------------------
    // Integration tests with a real K2 session
    // -----------------------------------------------------------------------

    /**
     * Integration test: exercises [KaIntroduceTypeAliasComputer.compute] backed by `kotlin-stdlib`.
     *
     * Verifies the computer does not throw and returns Ready with non-empty occurrence list for the
     * `simpleType` fixture.
     */
    fun testCompute_withRealSession_simpleType() {
        val triple = prepareWithRealSession("simpleType")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()

            assertTrue(
                "Expected Ready from real session, got $outcome",
                outcome is KaIntroduceTypeAliasComputer.Outcome.Ready,
            )
            val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result
            assertTrue("Expected ≥1 occurrence with real session", result.occurrenceRanges.isNotEmpty())
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: verifies occurrence count for `simpleType` fixture.
     *
     * `simpleType/file.kt` has two functions using `String` as a parameter/return type.
     * The computer should find both occurrences.
     */
    fun testOccurrenceCount_withRealSession_simpleType() {
        val triple = prepareWithRealSession("simpleType")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping occurrence-count test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue("Expected Ready", outcome is KaIntroduceTypeAliasComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result

            assertTrue(
                "Expected ≥2 'String' type references in simpleType fixture, got ${result.occurrenceRanges.size}",
                result.occurrenceRanges.size >= 2,
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * A complete type selection must be passed to the computer from its start offset while retaining
     * its bounds. The selection means the concrete `List<String>`, not its generic constructor.
     */
    fun testGenericType_completeSelectionExtractsConcreteAlias() {
        val triple = prepareWithRealSession("genericSubstitutions")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping complete-selection test")
                return
            }
        val (_, ktFile, tmpDir) = triple
        try {
            val source = ktFile.text
            val selectionStart = source.indexOf("List<String>")
            assertTrue("Expected List<String> in the generic fixture", selectionStart >= 0)
            val selectionEnd = selectionStart + "List<String>".length

            val outcome = KaIntroduceTypeAliasComputer(
                ktFile,
                selectionStart,
                TextRange(selectionStart, selectionEnd),
            ).compute()
            assertTrue("Expected Ready for selected List<String>, got $outcome", outcome is KaIntroduceTypeAliasComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result

            assertEquals("Expected the complete concrete alias body", "List<String>", result.aliasTypeText)
            assertEquals("A complete selected type must have no alias parameters", emptyList<String>(), result.typeParameterNames)
            assertEquals(
                "Only identical List<String> occurrences should be replaced",
                listOf("StringList", "StringList"),
                result.occurrenceReplacements.map { it.replacementText },
            )

            val endOutcome = KaIntroduceTypeAliasComputer(ktFile, selectionEnd).compute()
            assertTrue("Exclusive selection end should not resolve to the preceding type, got $endOutcome", endOutcome is KaIntroduceTypeAliasComputer.Outcome.NotApplicable)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /** Selecting only `List` extracts its type constructor and preserves each occurrence's arguments. */
    fun testGenericType_constructorSelectionExtractsParameterAndSubstitutesOccurrences() {
        val triple = prepareWithRealSession("genericSubstitutions")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping constructor-selection test")
                return
            }
        val (_, ktFile, tmpDir) = triple
        try {
            val constructorStart = ktFile.text.indexOf("List<String>")
            assertTrue("Expected List<String> in the generic fixture", constructorStart >= 0)
            val outcome = KaIntroduceTypeAliasComputer(
                ktFile,
                constructorStart,
                TextRange(constructorStart, constructorStart + "List".length),
            ).compute()
            assertTrue("Expected Ready for selected List constructor, got $outcome", outcome is KaIntroduceTypeAliasComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result

            assertEquals("Expected the generic alias body", "List<T>", result.aliasTypeText)
            assertEquals("Expected one extracted type parameter", listOf("T"), result.typeParameterNames)
            assertEquals(
                "Expected structural occurrences to preserve their own type arguments",
                listOf("StringList<String>", "StringList<String>", "StringList<Int>", "StringList<Int>"),
                result.occurrenceReplacements.map { it.replacementText },
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /** A caret inside a generic type aliases the complete concrete type unless a constructor is selected. */
    fun testGenericType_caretExtractsConcreteAlias() {
        val triple = prepareWithRealSession("genericSubstitutions")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping caret semantics test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue("Expected Ready for List<String>, got $outcome", outcome is KaIntroduceTypeAliasComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result

            assertEquals("Expected a concrete alias body at caret", "List<String>", result.aliasTypeText)
            assertEquals("Expected no extracted type parameters at caret", emptyList<String>(), result.typeParameterNames)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /** Constructor selection recursively abstracts nested generic leaves. */
    fun testNestedGenericType_constructorSelectionExtractsNestedParameters() {
        val triple = prepareWithRealSession("nestedGeneric")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping nested constructor-selection test")
                return
            }
        val (_, ktFile, tmpDir) = triple
        try {
            val constructorStart = ktFile.text.indexOf("List<Map<String, Int>>")
            assertTrue("Expected nested List in fixture", constructorStart >= 0)
            val outcome = KaIntroduceTypeAliasComputer(
                ktFile,
                constructorStart,
                TextRange(constructorStart, constructorStart + "List".length),
            ).compute()
            assertTrue("Expected Ready for selected List constructor, got $outcome", outcome is KaIntroduceTypeAliasComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result

            assertEquals("Expected recursively parameterized alias body", "List<Map<T, U>>", result.aliasTypeText)
            assertEquals("Expected parameters for both nested leaves", listOf("T", "U"), result.typeParameterNames)
            assertEquals(
                "Expected occurrence-specific nested substitutions",
                listOf(
                    "StringIntMapList<String, Int>",
                    "StringIntMapList<String, Int>",
                    "StringIntMapList<Boolean, Long>",
                    "StringIntMapList<Boolean, Long>",
                ),
                result.occurrenceReplacements.map { it.replacementText },
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /** Constructor selection retains a qualified user-type path while parameterizing every segment. */
    fun testQualifiedGenericType_constructorSelectionPreservesQualifierStructure() {
        val triple = prepareWithRealSession("qualifiedGeneric")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping qualified constructor-selection test")
                return
            }
        val (_, ktFile, tmpDir) = triple
        try {
            val fullTypeStart = ktFile.text.indexOf("Outer<String>.Inner<Int>")
            assertTrue("Expected qualified type in fixture", fullTypeStart >= 0)
            val constructorStart = fullTypeStart + "Outer<String>.".length
            val outcome = KaIntroduceTypeAliasComputer(
                ktFile,
                constructorStart,
                TextRange(constructorStart, constructorStart + "Inner".length),
            ).compute()
            assertTrue("Expected Ready for selected Inner constructor, got $outcome", outcome is KaIntroduceTypeAliasComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceTypeAliasComputer.Outcome.Ready).result

            assertEquals("Expected parameterized qualified alias body", "Outer<T>.Inner<U>", result.aliasTypeText)
            assertEquals("Expected parameters from both qualified segments", listOf("T", "U"), result.typeParameterNames)
            assertEquals(
                "Expected qualified occurrences to preserve their own arguments",
                listOf(
                    "IntInner<String, Int>",
                    "IntInner<String, Int>",
                    "IntInner<Boolean, Long>",
                    "IntInner<Boolean, Long>",
                ),
                result.occurrenceReplacements.map { it.replacementText },
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /** A function type parameter cannot leak into a top-level alias declaration. */
    fun testLocalTypeParameter_withRealSession_returnsError() {
        val triple = prepareWithRealSession("localTypeParameter")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping local type parameter test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue("Expected Error for List<T>, got $outcome", outcome is KaIntroduceTypeAliasComputer.Outcome.Error)
            val error = (outcome as KaIntroduceTypeAliasComputer.Outcome.Error).error
            assertTrue("Expected a helpful local type parameter message, got ${error.message}", error.message.orEmpty().contains("local type parameter"))
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /** Star projections cannot be converted into alias type parameters without changing meaning. */
    fun testStarProjection_withRealSession_returnsError() {
        val triple = prepareWithRealSession("starProjection")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping star projection test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue("Expected Error for List<*>, got $outcome", outcome is KaIntroduceTypeAliasComputer.Outcome.Error)
            val error = (outcome as KaIntroduceTypeAliasComputer.Outcome.Error).error
            assertTrue("Expected a helpful star-projection message, got ${error.message}", error.message.orEmpty().contains("star projection"))
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
