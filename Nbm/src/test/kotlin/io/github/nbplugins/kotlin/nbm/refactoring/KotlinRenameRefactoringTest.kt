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

import org.jetbrains.kotlin.refactorings.rename.KotlinRefactoringsFactory
import org.netbeans.junit.NbTestCase
import org.openide.filesystems.FileUtil
import org.openide.loaders.DataObject

/** Unit tests for the typed Kotlin filesystem-rename refactoring carriers. */
class KotlinRenameRefactoringTest : NbTestCase("KotlinRenameRefactoringTest") {

    /** Verifies a file carrier retains the selected Kotlin source and requested basename. */
    fun testFileCarrier_retainsSourceAndName() {
        val file = FileUtil.createMemoryFileSystem().root.createData("Before.kt")

        val refactoring = KotlinRenameFileRefactoring(file, "After")

        assertEquals(file, refactoring.file)
        assertEquals("After", refactoring.newName)
        assertFalse("the explicit action must stay physical-only", refactoring.updatePrimaryClass)
    }

    /** Verifies generic file Rename carries the matching-primary-class mode. */
    fun testFileCarrier_retainsPrimaryClassRenameMode() {
        val file = FileUtil.createMemoryFileSystem().root.createData("Before.kt")

        val refactoring = KotlinRenameFileRefactoring(file, "After", updatePrimaryClass = true)

        assertTrue("generic Rename must update a matching top-level class", refactoring.updatePrimaryClass)
    }

    /** Verifies semantic file Rename rejects a basename that cannot name a Kotlin class. */
    fun testFilePlugin_rejectsKeywordBasenameInSemanticMode() {
        val file = FileUtil.createMemoryFileSystem().root.createData("Before.kt")
        val plugin = KotlinRenameFilePlugin(KotlinRenameFileRefactoring(file, "class", updatePrimaryClass = true))

        assertNotNull(
            "a hard keyword must not be written as a class name",
            plugin.fastCheckParameters(),
        )
    }

    /** Verifies the physical-only explicit action still accepts a keyword basename. */
    fun testFilePlugin_acceptsKeywordBasenameInPhysicalMode() {
        val file = FileUtil.createMemoryFileSystem().root.createData("Before.kt")
        val plugin = KotlinRenameFilePlugin(KotlinRenameFileRefactoring(file, "class"))

        assertNull("a physical file may be named after a keyword", plugin.fastCheckParameters())
    }

    /** Verifies a directory carrier defaults to a physical-only rename. */
    fun testDirectoryCarrier_retainsPhysicalSourceAndName() {
        val directory = FileUtil.createMemoryFileSystem().root.createFolder("before")

        val refactoring = KotlinRenameDirectoryRefactoring(directory, "after")

        assertEquals(directory, refactoring.directory)
        assertEquals("after", refactoring.newName)
        assertFalse("the explicit action must stay physical-only", refactoring.updatePackage)
    }

    /** Verifies generic Rename carries the semantic package-update mode. */
    fun testDirectoryCarrier_retainsSemanticMode() {
        val directory = FileUtil.createMemoryFileSystem().root.createFolder("before")

        val refactoring = KotlinRenameDirectoryRefactoring(directory, "after", updatePackage = true)

        assertTrue("generic Rename must update packages and references", refactoring.updatePackage)
    }

    /** Verifies the file carrier bypasses the declaration-rename document applicability guard. */
    fun testFactory_createsFilePluginWithoutStyledDocument() {
        val file = FileUtil.createMemoryFileSystem().root.createData("Before.kt")

        val plugin = KotlinRefactoringsFactory().createInstance(KotlinRenameFileRefactoring(file, "After"))

        assertTrue("Filesystem file rename must use its typed plugin", plugin is KotlinRenameFilePlugin)
    }

    /** Verifies the folder carrier bypasses the declaration-rename document applicability guard. */
    fun testFactory_createsDirectoryPluginWithoutStyledDocument() {
        val directory = FileUtil.createMemoryFileSystem().root.createFolder("before")
        directory.createData("Source.kt")

        val plugin = KotlinRefactoringsFactory().createInstance(KotlinRenameDirectoryRefactoring(directory, "after"))

        assertTrue("Filesystem directory rename must use its typed plugin", plugin is KotlinRenameDirectoryPlugin)
    }

    /** Verifies the default DataObject editor path releases its lock before a physical file rename. */
    fun testFileApplyElement_renamesAndUndoesWithDefaultEditorTransaction() {
        val root = FileUtil.createMemoryFileSystem().root
        val before = root.createData("Before.kt")
        before.getOutputStream().use { it.write("fun before() = Unit\n".toByteArray()) }
        DataObject.find(before)

        val apply = KotlinRenameFileApplyElement(KotlinRenameFileRefactoring(before, "After"))
        apply.performChange()

        assertNull("apply must remove the original physical filename", root.getFileObject("Before.kt"))
        assertNotNull("apply must create the requested physical filename", root.getFileObject("After.kt"))
        apply.undoChange()

        assertNotNull("undo must restore the original physical filename", root.getFileObject("Before.kt"))
        assertNull("undo must remove the renamed physical filename", root.getFileObject("After.kt"))
    }

    /** Verifies K2 rename replacements are applied without offset drift. */
    fun testApplyTextChanges_appliesDescendingRanges() {
        val changes = listOf(
            io.github.nbplugins.kotlin.refactoring.TextChange(0, 3, "Baz", "Foo"),
            io.github.nbplugins.kotlin.refactoring.TextChange(9, 12, "Baz", "Foo"),
        )

        assertEquals("Baz uses Baz", applyTextChanges("Foo uses Foo", changes))
    }
}
