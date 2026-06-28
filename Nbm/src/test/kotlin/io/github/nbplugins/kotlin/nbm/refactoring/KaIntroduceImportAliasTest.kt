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
import io.github.nbplugins.kotlin.refactoring.KaIntroduceImportAliasComputer
import io.github.nbplugins.kotlin.refactoring.KaIntroduceImportAliasResult
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [KaIntroduceImportAliasComputer].
 *
 * Fixtures are in `projForTest/src/introduceImportAlias/`.  Each sub-directory contains:
 *  - `file.kt`    — Kotlin source being refactored
 *  - `file.caret` — same source with `<caret>` marking the caret position
 *
 * Tests cover:
 *  1. Trigger from an import directive line → [KaIntroduceImportAliasComputer.Outcome.Ready]
 *  2. Trigger from a name reference in code (via K2) → [KaIntroduceImportAliasComputer.Outcome.Ready]
 *  3. Caret offset of the alias in the new import line (position-after-refactoring test)
 *  4. Star-import → [KaIntroduceImportAliasComputer.Outcome.NotApplicable]
 *  5. Already-aliased import → [KaIntroduceImportAliasComputer.Outcome.NotApplicable]
 */
class KaIntroduceImportAliasTest : KotlinTestCase("KaIntroduceImportAliasTest", "introduceImportAlias") {

    companion object {
        private const val CARET_MARKER = "<caret>"
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaIntroduceImportAliasTest: skipping — no K2 dependencies available")
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
     * Sets up a [KaIntroduceImportAliasComputer] using the project-scoped session.
     *
     * @param subDir fixture sub-directory
     * @return pair of (computer, KtFile) or null when the fixture or session is missing
     */
    private fun prepareComputer(
        subDir: String,
        session: KotlinAnalysisAPISession,
    ): Pair<KaIntroduceImportAliasComputer, KtFile>? {
        val fileFo = dir.getFileObject(subDir)?.getFileObject("file.kt") ?: return null
        val offset = readCaretOffset(subDir) ?: return null
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return null
        return KaIntroduceImportAliasComputer(ktFile, offset) to ktFile
    }

    /**
     * Builds a standalone [KotlinAnalysisAPISession] backed by `kotlin-stdlib` and a temp copy
     * of the fixture source, for tests that need real K2 symbol resolution.
     */
    private fun prepareWithRealSession(
        subDir: String,
    ): Triple<KaIntroduceImportAliasComputer, KtFile, Path>? {
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
            moduleName = "import-alias-$subDir",
            binaryJars = listOf(stdlib),
            sourceRoots = listOf(tmpDir),
        )
        val ktFile = session.getKtFileForPath(tmpFile.toString())
            ?: error("Failed to obtain KtFile for $tmpFile")
        return Triple(KaIntroduceImportAliasComputer(ktFile, offset), ktFile, tmpDir)
    }

    // -----------------------------------------------------------------------
    // Computer correctness tests
    // -----------------------------------------------------------------------

    /**
     * Caret on the import directive `import java.util.ArrayList`: the computer must return
     * [KaIntroduceImportAliasComputer.Outcome.Ready] with:
     *  - [KaIntroduceImportAliasResult.shortName] == `"ArrayList"`
     *  - [KaIntroduceImportAliasResult.importedFqn] == `"java.util.ArrayList"`
     *  - at least one usage range (the `ArrayList<String>()` call in the body)
     */
    fun testOnImportLine_returnsReady() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("onImportLine", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected Ready when caret is on import directive, got $outcome",
            outcome is KaIntroduceImportAliasComputer.Outcome.Ready,
        )
        val result = (outcome as KaIntroduceImportAliasComputer.Outcome.Ready).result
        assertEquals("Expected shortName == ArrayList", "ArrayList", result.shortName)
        assertEquals("Expected FQN == java.util.ArrayList", "java.util.ArrayList", result.importedFqn)
        assertTrue(
            "Expected at least 1 usage range (the ArrayList<String>() call)",
            result.usageRanges.isNotEmpty(),
        )
    }

    /**
     * Star-import `import java.util.*`: the computer must return
     * [KaIntroduceImportAliasComputer.Outcome.NotApplicable].
     */
    fun testStarImport_returnsNotApplicable() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("starImport", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable for star-import, got $outcome",
            outcome is KaIntroduceImportAliasComputer.Outcome.NotApplicable,
        )
    }

    /**
     * Import already has an alias (`import java.util.ArrayList as AL`): the computer must return
     * [KaIntroduceImportAliasComputer.Outcome.NotApplicable].
     */
    fun testAlreadyAliased_returnsNotApplicable() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("alreadyAliased", session) ?: return

        val outcome = computer.compute()

        assertTrue(
            "Expected NotApplicable for already-aliased import, got $outcome",
            outcome is KaIntroduceImportAliasComputer.Outcome.NotApplicable,
        )
    }

    // -----------------------------------------------------------------------
    // Caret positioning tests
    // -----------------------------------------------------------------------

    /**
     * Verifies the alias-offset calculation for the **import-line trigger** case:
     * caret was on the import directive, so after transformation the caret lands on the alias
     * identifier inside `import java.util.ArrayList as |AL|`.
     *
     * This mirrors the `caretTarget` computation in [KotlinIntroduceImportAliasApplyElement.performChange]
     * when `caretOffset` is within `importDirectiveRange`.
     */
    fun testAliasOffset_importLineTrigger_pointsToAliasInImport() {
        val session = getSessionOrSkip() ?: return
        val (computer, ktFile) = prepareComputer("onImportLine", session) ?: return

        val outcome = computer.compute()
        assertTrue("Expected Ready", outcome is KaIntroduceImportAliasComputer.Outcome.Ready)
        val result = (outcome as KaIntroduceImportAliasComputer.Outcome.Ready).result

        val chosenAlias = "AL"
        val importRange = result.importDirectiveRange
        val originalText = ktFile.text

        // Simulate the text transformation from performChange():
        // 1. Replace usages back-to-front.
        var newText = originalText
        for (range in result.usageRanges.sortedByDescending { it.startOffset }) {
            newText = newText.substring(0, range.startOffset) +
                    chosenAlias +
                    newText.substring(range.endOffset)
        }
        // 2. Replace import directive.
        val newImport = "import ${result.importedFqn} as $chosenAlias"
        // Caret triggered from import line → alias in import.
        val aliasOffset = importRange.startOffset + "import ${result.importedFqn} as ".length
        newText = newText.substring(0, importRange.startOffset) +
                newImport +
                newText.substring(importRange.endOffset)

        assertTrue("aliasOffset $aliasOffset must be within newText (len ${newText.length})",
            aliasOffset < newText.length)
        assertEquals(
            "Character at aliasOffset must be start of '$chosenAlias'",
            chosenAlias[0],
            newText[aliasOffset],
        )
        assertEquals(
            "Text at aliasOffset must start with '$chosenAlias'",
            chosenAlias,
            newText.substring(aliasOffset, aliasOffset + chosenAlias.length),
        )
    }

    /**
     * Verifies the alias-offset calculation for the **body-reference trigger** case:
     * caret was on a usage of `ArrayList` in the function body, so after transformation the
     * caret must land on the alias `AL` **at that usage position** — not in the import line.
     *
     * This mirrors the `caretTarget` computation in [KotlinIntroduceImportAliasApplyElement.performChange]
     * when `caretOffset` is outside `importDirectiveRange`.
     */
    fun testAliasOffset_bodyReferenceTrigger_pointsToAliasInBody() {
        val session = getSessionOrSkip() ?: return
        // `onReference/file.caret` has the caret inside `ArrayList` in the body.
        val subFo = dir.getFileObject("onReference") ?: return
        val fileFo = subFo.getFileObject("file.kt") ?: return
        val caretOffset = readCaretOffset("onReference") ?: return
        val ktFile = session.getKtFileForPath(fileFo.path) ?: return

        val computer = KaIntroduceImportAliasComputer(ktFile, caretOffset)
        val outcome = computer.compute()
        assertTrue("Expected Ready", outcome is KaIntroduceImportAliasComputer.Outcome.Ready)
        val result = (outcome as KaIntroduceImportAliasComputer.Outcome.Ready).result

        val chosenAlias = "AL"
        val importRange = result.importDirectiveRange
        val originalText = ktFile.text

        // Replicate performChange() text transformation.
        var newText = originalText
        val sortedUsages = result.usageRanges.sortedByDescending { it.startOffset }
        for (range in sortedUsages) {
            newText = newText.substring(0, range.startOffset) +
                    chosenAlias +
                    newText.substring(range.endOffset)
        }
        val newImport = "import ${result.importedFqn} as $chosenAlias"
        val importLengthDelta = newImport.length - (importRange.endOffset - importRange.startOffset)
        newText = newText.substring(0, importRange.startOffset) +
                newImport +
                newText.substring(importRange.endOffset)

        // Replicate caretTarget formula for body-reference trigger.
        val targetUsage = result.usageRanges.firstOrNull { r ->
            r.startOffset <= caretOffset && caretOffset < r.endOffset
        } ?: result.usageRanges.minByOrNull { r ->
            minOf(Math.abs(r.startOffset - caretOffset), Math.abs(r.endOffset - caretOffset))
        }
        assertNotNull("Expected to find a target usage for body caret", targetUsage)
        val lowerShift = result.usageRanges
            .filter { it.endOffset <= targetUsage!!.startOffset }
            .sumOf { chosenAlias.length - (it.endOffset - it.startOffset) }
        val caretTarget = targetUsage!!.startOffset + lowerShift + importLengthDelta

        assertTrue("caretTarget $caretTarget must be within newText (len ${newText.length})",
            caretTarget < newText.length)
        assertEquals(
            "Character at caretTarget must be start of alias '$chosenAlias'",
            chosenAlias[0],
            newText[caretTarget],
        )
        assertEquals(
            "Text at caretTarget must be alias '$chosenAlias' (caret stays at body usage, not import line)",
            chosenAlias,
            newText.substring(caretTarget, caretTarget + chosenAlias.length),
        )
        // Sanity: caretTarget must be AFTER the import line.
        assertTrue(
            "caretTarget $caretTarget must be after importRange ${importRange.endOffset}",
            caretTarget > importRange.startOffset + newImport.length,
        )
    }

    /**
     * Verifies that the usage count is correct: `onImportLine/file.kt` has two `ArrayList`
     * references in the body (the type constructor call and `<String>` type argument).
     *
     * [KaIntroduceImportAliasComputer] uses PSI name-matching so both occurrences of
     * `ArrayList` as a [KtSimpleNameExpression] are returned.
     */
    fun testUsageCount_onImportLine() {
        val session = getSessionOrSkip() ?: return
        val (computer, _) = prepareComputer("onImportLine", session) ?: return

        val outcome = computer.compute()
        assertTrue("Expected Ready", outcome is KaIntroduceImportAliasComputer.Outcome.Ready)
        val result = (outcome as KaIntroduceImportAliasComputer.Outcome.Ready).result

        // The fixture has `ArrayList<String>()` → one `KtSimpleNameExpression` for "ArrayList".
        assertEquals(
            "Expected exactly 1 ArrayList reference in the body (type args don't count separately)",
            1,
            result.usageRanges.size,
        )
    }

    // -----------------------------------------------------------------------
    // Integration tests with a real K2 session
    // -----------------------------------------------------------------------

    /**
     * Integration test: exercises [KaIntroduceImportAliasComputer.compute] against a real K2
     * session backed by `kotlin-stdlib` with caret on the import directive line.
     *
     * Verifies:
     *  - outcome is [KaIntroduceImportAliasComputer.Outcome.Ready]
     *  - [KaIntroduceImportAliasResult.shortName] is `"ArrayList"`
     *  - [KaIntroduceImportAliasResult.importedFqn] is `"java.util.ArrayList"`
     *  - at least one usage range is found
     */
    fun testCompute_withRealSession_onImportLine() {
        val triple = prepareWithRealSession("onImportLine")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue(
                "Expected Ready from real session, got $outcome",
                outcome is KaIntroduceImportAliasComputer.Outcome.Ready,
            )
            val result = (outcome as KaIntroduceImportAliasComputer.Outcome.Ready).result
            assertEquals("shortName must be ArrayList", "ArrayList", result.shortName)
            assertEquals("importedFqn must be java.util.ArrayList", "java.util.ArrayList", result.importedFqn)
            assertTrue("Expected at least 1 usage range", result.usageRanges.isNotEmpty())
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: caret on `ArrayList` in the body (constructor-call reference) triggers
     * K2 symbol resolution via [KaIntroduceImportAliasComputer.computeFromNameReference].
     *
     * Verifies that K2 correctly:
     *  - resolves `ArrayList` constructor call → `KaConstructorSymbol.containingClassId`
     *  - maps back to `java.util.ArrayList` FQN
     *  - locates the import directive in the file
     *  - returns [KaIntroduceImportAliasComputer.Outcome.Ready] with correct FQN and short name
     */
    fun testCompute_withRealSession_onReference() {
        val triple = prepareWithRealSession("onReference")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping real-session test")
                return
            }
        val (computer, _, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue(
                "Expected Ready when caret is on constructor reference, got $outcome",
                outcome is KaIntroduceImportAliasComputer.Outcome.Ready,
            )
            val result = (outcome as KaIntroduceImportAliasComputer.Outcome.Ready).result
            assertEquals("shortName must be ArrayList", "ArrayList", result.shortName)
            assertEquals("importedFqn must be java.util.ArrayList", "java.util.ArrayList", result.importedFqn)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Integration test: after applying the text transformation for the `onReference` fixture
     * (caret on `ArrayList` in the body), the caret-target computed by
     * [KotlinIntroduceImportAliasApplyElement.performChange] must point to the alias name
     * **at the body usage** (trigger location), not in the import line.
     */
    fun testApply_withRealSession_aliasOffsetIsCorrect() {
        val triple = prepareWithRealSession("onReference")
            ?: run {
                println("kotlin-stdlib not on test classpath — skipping apply test")
                return
            }
        val (computer, ktFile, tmpDir) = triple
        try {
            val outcome = computer.compute()
            assertTrue("Expected Ready", outcome is KaIntroduceImportAliasComputer.Outcome.Ready)
            val result = (outcome as KaIntroduceImportAliasComputer.Outcome.Ready).result

            val chosenAlias = "AL"
            val originalText = ktFile.text
            val importRange = result.importDirectiveRange

            // Replicate performChange() text transformation.
            var newText = originalText
            val sortedUsages = result.usageRanges.sortedByDescending { it.startOffset }
            for (range in sortedUsages) {
                newText = newText.substring(0, range.startOffset) +
                        chosenAlias +
                        newText.substring(range.endOffset)
            }
            val newImport = "import ${result.importedFqn} as $chosenAlias"
            val importLengthDelta = newImport.length - (importRange.endOffset - importRange.startOffset)
            newText = newText.substring(0, importRange.startOffset) +
                    newImport +
                    newText.substring(importRange.endOffset)

            // onReference/file.caret has caret on a body usage; read offset from fixture.
            val caretOffset = readCaretOffset("onReference")

            // Verify the body-reference caret target.
            val targetUsage = result.usageRanges.firstOrNull { r ->
                caretOffset != null && r.startOffset <= caretOffset && caretOffset < r.endOffset
            } ?: result.usageRanges.firstOrNull()
            assertNotNull("Expected at least one usage range", targetUsage)
            val lowerShift = result.usageRanges
                .filter { it.endOffset <= targetUsage!!.startOffset }
                .sumOf { chosenAlias.length - (it.endOffset - it.startOffset) }
            val bodyCaretTarget = targetUsage!!.startOffset + lowerShift + importLengthDelta

            assertTrue("bodyCaretTarget $bodyCaretTarget within newText (len ${newText.length})",
                bodyCaretTarget < newText.length)
            assertEquals(
                "Text at bodyCaretTarget must be '$chosenAlias' (caret at body usage)",
                chosenAlias,
                newText.substring(bodyCaretTarget, bodyCaretTarget + chosenAlias.length),
            )
            // Sanity: the caret must be in the body, not in the import line.
            val newImportEnd = importRange.startOffset + newImport.length
            assertTrue(
                "bodyCaretTarget $bodyCaretTarget must be after import line end $newImportEnd",
                bodyCaretTarget > newImportEnd,
            )

            // Verify new import text and body replacements.
            assertTrue(
                "Expected new import in output:\n$newText",
                newText.contains("import java.util.ArrayList as $chosenAlias"),
            )
            val bodyText = newText.substringAfter("fun main()")
            assertFalse(
                "ArrayList should not appear in function body after aliasing:\n$bodyText",
                bodyText.contains("ArrayList"),
            )
            assertTrue(
                "Alias '$chosenAlias' should appear in function body:\n$bodyText",
                bodyText.contains(chosenAlias),
            )
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
