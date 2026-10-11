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

import org.netbeans.junit.NbTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Unit tests for [KotlinChangePackageDirectoryAction]. */
class KotlinChangePackageDirectoryActionTest : NbTestCase("KotlinChangePackageDirectoryActionTest") {

    /** Verifies source discovery stays on the NodeAction worker before Swing UI is marshalled to EDT. */
    fun testAsynchronous_allowsBackgroundSourceDiscovery() {
        assertTrue(
            "Source discovery must run in NetBeans' worker before the action schedules its Swing UI",
            TestAction().isAsynchronous(),
        )
    }

    /** Verifies folder actions are discovered only through the shared Refactor submenu. */
    fun testLayer_keepsFolderActionsOutOfTheTopLevelContextMenu() {
        val layer = repositoryRoot().resolve("Nbm/src/main/resources/org/jetbrains/kotlin/layer.xml")
        val contents = Files.readString(layer)

        assertFalse(
            "Change Package must not have a direct folder-context shadow",
            contents.contains("<file name=\"KotlinChangePackageDirectoryAction.shadow\">"),
        )
        assertFalse(
            "Rename Directory must not have a direct folder-context shadow",
            contents.contains("<file name=\"KotlinRenameDirectoryAction.shadow\">"),
        )
        assertTrue(
            "Change Package directory action must remain globally available to RefactoringAll",
            contents.contains("KotlinChangePackageDirectoryAction.instance"),
        )
        assertTrue(
            "Change Package editor action must remain registered for editor contexts",
            contents.contains("KotlinChangePackageAction.instance"),
        )
        assertPositions(
            contents,
            "Folder Refactor menu must route Change Package to its node action",
            "io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangePackageDirectoryAction.shadow",
            267,
            "io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangeSignatureAction.shadow",
            270,
        )
        assertFalse(
            "Folder Refactor menu must not route Change Package through the editor action",
            contents.contains("<file name=\"io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangePackageAction.shadow\">"),
        )
        assertTrue(
            "Rename Directory must remain globally available to RefactoringAll",
            contents.contains("KotlinRenameDirectoryAction.instance"),
        )
        assertTrue(
            "Kotlin file nodes must place the Refactor submenu inside their loader Actions folder",
            contents.contains(
                "<file name=\"RefactoringAll.shadow\">\n" +
                    "                        <attr name=\"originalFile\" " +
                    "stringvalue=\"Actions/Refactoring/RefactoringAll.instance\"/>",
            ),
        )
        assertTrue(
            "Refactor must register the node-aware F7 action",
            contents.contains(
                "<file name=\"io-github-nbplugins-kotlin-nbm-refactoring-KotlinRenameFileNodeAction.shadow\">\n" +
                    "                <attr name=\"originalFile\" " +
                    "stringvalue=\"Actions/Refactoring/io-github-nbplugins-kotlin-nbm-refactoring-KotlinRenameFileNodeAction.instance\"/>"
            ),
        )
        assertFalse(
            "The editor-only F7 action must not compete with the node action in RefactoringAll",
            contents.contains("<file name=\"io-github-nbplugins-kotlin-nbm-refactoring-KotlinRenameFileAction.shadow\">"),
        )
        assertPositions(
            contents,
            "F7 file rename must precede the undo separator",
            "io-github-nbplugins-kotlin-nbm-refactoring-KotlinRenameFileNodeAction.shadow",
            2665,
            "separator-before-undo-redo.instance",
            3000,
        )
        assertPositions(
            contents,
            "F7 directory rename must precede the undo separator",
            "io-github-nbplugins-kotlin-nbm-refactoring-KotlinRenameDirectoryAction.shadow",
            2666,
            "separator-before-undo-redo.instance",
            3000,
        )
        assertPositions(
            contents,
            "Undo must remain after the final separator",
            "separator-before-undo-redo.instance",
            3000,
            "org-jetbrains-kotlin-refactorings-KotlinRefactoringUndoAction.shadow",
            3100,
        )
        assertPositions(
            contents,
            "Redo must remain the final Refactor entry",
            "org-jetbrains-kotlin-refactorings-KotlinRefactoringUndoAction.shadow",
            3100,
            "org-jetbrains-kotlin-refactorings-KotlinRefactoringRedoAction.shadow",
            3200,
        )
    }

    /** Verifies two layer entries retain the requested ordering and explicit positions. */
    private fun assertPositions(
        contents: String,
        message: String,
        firstName: String,
        firstPosition: Int,
        secondName: String,
        secondPosition: Int,
    ) {
        val firstMarker = "<file name=\"$firstName\">"
        val secondMarker = "<file name=\"$secondName\">"
        val firstStart = contents.indexOf(firstMarker)
        val secondStart = contents.indexOf(secondMarker)
        assertTrue("$message: missing $firstName", firstStart >= 0)
        assertTrue("$message: missing $secondName", secondStart >= 0)
        assertTrue("$message: layer entries are out of order", firstStart < secondStart)
        assertTrue(
            "$message: $firstName position mismatch",
            contents.substring(firstStart, contents.indexOf("</file>", firstStart)).contains("intvalue=\"$firstPosition\""),
        )
        assertTrue(
            "$message: $secondName position mismatch",
            contents.substring(secondStart, contents.indexOf("</file>", secondStart)).contains("intvalue=\"$secondPosition\""),
        )
    }

    /** Finds the checkout root from the NBM module's Surefire working directory. */
    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) && Files.isDirectory(candidate.resolve("Nbm"))) {
                return candidate
            }
            candidate = candidate.parent
        }
        fail("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        error("unreachable")
    }

    /** Exposes NodeAction's protected dispatcher contract for this focused test. */
    private class TestAction : KotlinChangePackageDirectoryAction() {
        fun isAsynchronous(): Boolean = asynchronous()
    }
}
