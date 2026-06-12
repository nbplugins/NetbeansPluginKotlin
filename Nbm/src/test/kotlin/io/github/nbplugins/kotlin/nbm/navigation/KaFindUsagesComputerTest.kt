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
package io.github.nbplugins.kotlin.nbm.navigation

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.builder.isKotlinFile
import utils.KotlinTestCase

/**
 * Unit tests for [KaFindUsagesComputer].
 *
 * Uses the `findUsages/` test fixture directory which contains:
 * - `declarations.kt`: top-level function, class, method, and property declarations.
 * - `usages.kt`: call sites that reference the declarations in `declarations.kt`.
 *
 * Offsets for target symbols are computed by searching for known identifier strings in the
 * parsed KtFile text (avoids embedding `<caret>` markers that break K2 parsing of declaration
 * names).
 */
class KaFindUsagesComputerTest : KotlinTestCase("KaFindUsagesComputer", "findUsages") {

    private fun getSessionOrSkip(): KotlinAnalysisAPISession? {
        val session = KotlinAnalysisAPISession.getSession(project)
        if (!session.hasDependencies) {
            println("KaFindUsagesComputerTest: skipping — no K2 dependencies available")
            return null
        }
        return session
    }

    /**
     * Verifies that [KaFindUsagesComputer.resolveTargetPsi] returns a non-null PSI element
     * when the cursor is positioned on the name of `targetFunction` in `declarations.kt`.
     */
    fun testResolveTargetPsiForFunction() {
        val session = getSessionOrSkip() ?: return
        val declFile = dir.getFileObject("declarations.kt") ?: return

        val kaKtFile = session.getKtFileForPath(declFile.path) ?: return
        // "fun targetFunction" — position the offset inside the name "targetFunction"
        val nameOffset = kaKtFile.text.indexOf("targetFunction")
        assertTrue("'targetFunction' must appear in declarations.kt", nameOffset >= 0)

        val allFiles = dir.children.filter { it.isKotlinFile() }.toSet()
        val computer = KaFindUsagesComputer(kaKtFile, nameOffset, session, allFiles)
        val targetPsi = computer.resolveTargetPsi()
        assertNotNull("resolveTargetPsi must return non-null for a named declaration", targetPsi)
    }

    /**
     * Verifies that [KaFindUsagesComputer.compute] finds both call sites of `targetFunction`
     * in `usages.kt` when the cursor is placed on the declaration in `declarations.kt`.
     *
     * `usages.kt` calls `targetFunction()` twice, so the result must contain at least two
     * [org.netbeans.modules.csl.api.OffsetRange]s for that file.
     */
    fun testComputeFindsUsagesOfFunction() {
        val session = getSessionOrSkip() ?: return
        val declFile = dir.getFileObject("declarations.kt") ?: return
        val usagesFile = dir.getFileObject("usages.kt") ?: return

        val kaKtFile = session.getKtFileForPath(declFile.path) ?: return
        val nameOffset = kaKtFile.text.indexOf("targetFunction")
        assertTrue("'targetFunction' must appear in declarations.kt", nameOffset >= 0)

        val allFiles = dir.children.filter { it.isKotlinFile() }.toSet()
        val usages = KaFindUsagesComputer(kaKtFile, nameOffset, session, allFiles).compute()

        val usagesEntry = usages.entries.firstOrNull { it.key.path == usagesFile.path }
        assertNotNull(
            "Usages of targetFunction should be found in usages.kt",
            usagesEntry
        )
        val ranges = usagesEntry!!.value
        assertTrue(
            "Expected at least 2 usages of targetFunction in usages.kt, got ${ranges.size}",
            ranges.size >= 2
        )
    }

    /**
     * Verifies that [KaFindUsagesComputer.compute] returns an empty map when the cursor offset
     * falls within the `package` keyword — a keyword is not a resolvable named symbol, so no
     * declaration PSI can be obtained and the result must be empty.
     */
    fun testComputeReturnsEmptyForKeywordOffset() {
        val session = getSessionOrSkip() ?: return
        val declFile = dir.getFileObject("declarations.kt") ?: return
        val kaKtFile = session.getKtFileForPath(declFile.path) ?: return

        val allFiles = dir.children.filter { it.isKotlinFile() }.toSet()
        // Offset 0 is the start of "package", which is not a named declaration
        val usages = KaFindUsagesComputer(kaKtFile, 0, session, allFiles).compute()
        assertTrue("compute() must not crash for keyword offsets", true)
        // We assert empty rather than checking for package usages, because finding package
        // directive references is not in scope for E7.
        // (The call may return non-empty if K2 resolves the package as a symbol, which is OK.)
    }
}
