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
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.nodes.AbstractNode
import org.openide.nodes.Children
import org.openide.nodes.Node
import org.openide.util.lookup.Lookups
import java.nio.file.Files
import java.nio.file.Path

/** Unit tests for [KotlinChangePackageNodeAction]. */
class KotlinChangePackageNodeActionTest : NbTestCase("KotlinChangePackageNodeActionTest") {

    /** Verifies source discovery stays on the NodeAction worker before Swing UI is marshalled to EDT. */
    fun testAsynchronous_allowsBackgroundSourceDiscovery() {
        assertTrue(
            "Source discovery must run in NetBeans' worker before the action schedules its Swing UI",
            TestAction().isAsynchronous(),
        )
    }

    /**
     * Verifies the action is enabled inside a Kotlin editor, where the active node is the edited
     * file rather than a folder.
     */
    fun testEnable_acceptsOneKotlinFileNode() {
        val root = newRoot()

        assertTrue(TestAction().isEnabledFor(node(root.createData("Sample.kt"))))
    }

    /** Verifies a plain Kotlin file node resolves to that file, not to its parent folder. */
    fun testSelectedSource_usesKotlinFileNode() {
        val root = newRoot()
        val file = root.createData("Sample.kt")

        assertEquals(file, TestAction().selectedSource(arrayOf(node(file))))
    }

    /** Verifies the folder Refactor entry keeps working for a directory with Kotlin descendants. */
    fun testEnable_acceptsFolderContainingKotlinSources() {
        val root = newRoot()
        val folder = root.createFolder("pkg")
        folder.createData("Sample.kt")

        assertTrue(TestAction().isEnabledFor(node(folder)))
    }

    /** Verifies a plain folder node resolves to the folder, keeping the recursive change operation. */
    fun testSelectedSource_usesFolderNode() {
        val root = newRoot()
        val folder = root.createFolder("pkg")
        folder.createData("Sample.kt")

        assertEquals(folder, TestAction().selectedSource(arrayOf(node(folder))))
    }

    /** Verifies a folder without Kotlin sources cannot start a Kotlin package change. */
    fun testEnable_rejectsFolderWithoutKotlinSources() {
        val root = newRoot()
        val folder = root.createFolder("pkg")
        folder.createData("Sample.java")

        assertFalse(TestAction().isEnabledFor(node(folder)))
    }

    /** Verifies Java files do not offer a Kotlin package change even though they are file nodes. */
    fun testEnable_rejectsNonKotlinFileNode() {
        val root = newRoot()

        assertFalse(TestAction().isEnabledFor(node(root.createData("Sample.java"))))
    }

    /** Verifies multi-selection stays disabled because the UI edits one file or one folder. */
    fun testEnable_rejectsMultipleNodes() {
        val root = newRoot()

        assertFalse(
            TestAction().isEnabledFor(node(root.createData("First.kt")), node(root.createData("Second.kt"))),
        )
    }

    /** Verifies a caret on an import directive targets the imported package's sources. */
    fun testImportTargetAt_resolvesImportedPackage() {
        val root = newRoot()
        val widget = root.createData("Widget.kt")
        val text = "package usage\n\nimport a.b.Widget\n"

        val target = TestAction().importTargetAt(text, text.indexOf("a.b")) { packageName ->
            if (packageName == "a.b") listOf(widget) else emptyList()
        }

        assertEquals(KotlinImportPackageTarget.Declared("a.b", listOf(widget)), target)
    }

    /** Verifies a caret outside an import keeps the ordinary file-package behaviour. */
    fun testImportTargetAt_ignoresCaretOutsideImport() {
        val text = "import a.b.Widget\n\nfun use() = 1\n"

        assertNull(TestAction().importTargetAt(text, text.indexOf("use")) { listOf() })
    }

    /** Verifies an import of a library package is reported instead of changing the file package. */
    fun testImportTargetAt_reportsLibraryImport() {
        val text = "import kotlin.collections.List\n"

        val target = TestAction().importTargetAt(text, text.indexOf("List")) { emptyList() }

        assertEquals(KotlinImportPackageTarget.External("kotlin.collections"), target)
    }

    /** Verifies folder actions are discovered only through the shared Refactor submenu. */
    fun testLayer_keepsFolderActionsOutOfTheTopLevelContextMenu() {
        val layer = repositoryRoot().resolve("Nbm/src/main/resources/org/jetbrains/kotlin/layer.xml")
        val contents = Files.readString(layer)

        assertFalse(
            "Change Package must not have a direct folder-context shadow",
            contents.contains("<file name=\"KotlinChangePackageNodeAction.shadow\">"),
        )
        assertFalse(
            "Rename Directory must not have a direct folder-context shadow",
            contents.contains("<file name=\"KotlinRenameDirectoryAction.shadow\">"),
        )
        assertTrue(
            "Change Package node action must remain globally available to RefactoringAll",
            contents.contains("KotlinChangePackageNodeAction.instance"),
        )
        assertTrue(
            "Change Package editor action must remain registered for editor contexts",
            contents.contains("KotlinChangePackageAction.instance"),
        )
        assertPositions(
            contents,
            "Refactor menu must route Change Package to its node action",
            "io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangePackageNodeAction.shadow",
            267,
            "io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangeSignatureAction.shadow",
            270,
        )
        assertFalse(
            "Refactor menu must not route Change Package through the editor action",
            contents.contains("<file name=\"io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangePackageAction.shadow\">"),
        )
        assertTrue(
            "Rename Directory must remain globally available to RefactoringAll",
            contents.contains("KotlinRenameDirectoryAction.instance"),
        )
        assertTrue(
            "Generic folder nodes must place the Refactor submenu inside their loader Actions folder",
            contents.contains(
                "<folder name=\"folder\">\n" +
                    "            <folder name=\"any\">\n" +
                    "                <folder name=\"Actions\">\n" +
                    "                    <file name=\"RefactoringAll.shadow\">\n" +
                    "                        <attr name=\"originalFile\" " +
                    "stringvalue=\"Actions/Refactoring/RefactoringAll.instance\"/>",
            ),
        )
        assertTrue(
            "Kotlin file nodes must create the Refactor submenu in their loader Actions folder",
            contents.contains(
                "<file name=\"RefactoringAll.instance\">\n" +
                    "                        <attr name=\"instanceCreate\" " +
                    "methodvalue=\"org.netbeans.modules.refactoring.api.ui.RefactoringActionsFactory.popupSubmenuAction\"/>",
            ),
        )
        assertTrue(
            "Kotlin file nodes must hide the ordinary Rename entry instead of showing it beside Refactor",
            contents.contains("<file name=\"RenameAction.shadow_hidden\"/>"),
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

    /** Creates an empty in-memory filesystem root so node lookups expose real file objects. */
    private fun newRoot() = FileUtil.createMemoryFileSystem().root

    /** Wraps a real file object in the node lookup shape NetBeans uses for explorer selections. */
    private fun node(value: Any): Node = AbstractNode(Children.LEAF, Lookups.singleton(value))

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

    /** Exposes NodeAction's protected dispatcher and resolution contracts for this focused test. */
    private class TestAction : KotlinChangePackageNodeAction() {
        fun isAsynchronous(): Boolean = asynchronous()

        fun isEnabledFor(vararg nodes: Node): Boolean = enable(arrayOf(*nodes))

        fun selectedSource(nodes: Array<Node>) = KotlinChangePackageNodeAction.selectedSource(nodes)

        fun importTargetAt(
            text: CharSequence,
            caretOffset: Int,
            filesInPackage: (String) -> List<FileObject>,
        ) = KotlinChangePackageNodeAction.importTargetAt(text, caretOffset, filesInPackage)
    }
}
