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
import org.openide.filesystems.FileUtil

/** Unit tests for [KotlinFilesystemRenameUI] labels. */
class KotlinFilesystemRenameUITest : NbTestCase("KotlinFilesystemRenameUITest") {

    /** Verifies the explicit action keeps its physical-only title and description. */
    fun testPhysicalDirectoryRenameLabels() {
        val directory = FileUtil.createMemoryFileSystem().root.createFolder("before")
        val ui = KotlinFilesystemRenameUI(KotlinRenameDirectoryRefactoring(directory, "after"), true, "before")

        assertEquals("Rename Kotlin Package Directory", ui.name)
        assertTrue(ui.description, ui.description.contains("Rename Kotlin package directory 'before'"))
        assertFalse("the physical-only description must not promise package updates", ui.description.contains("package declarations"))
    }

    /** Verifies generic Rename announces that package declarations follow the directory rename. */
    fun testSemanticDirectoryRenameLabels() {
        val directory = FileUtil.createMemoryFileSystem().root.createFolder("before")
        val ui = KotlinFilesystemRenameUI(
            KotlinRenameDirectoryRefactoring(directory, "after", updatePackage = true),
            true,
            "before",
        )

        assertEquals("Rename Kotlin Package", ui.name)
        assertTrue(ui.description, ui.description.contains("package declarations"))
    }

    /** Verifies the explicit file action keeps its physical-only labels. */
    fun testPhysicalFileRenameLabels() {
        val file = FileUtil.createMemoryFileSystem().root.createData("Before.kt")
        val ui = KotlinFilesystemRenameUI(KotlinRenameFileRefactoring(file, "After"), false, "Before.kt")

        assertEquals("Rename Kotlin File", ui.name)
        assertEquals("Rename Kotlin file 'Before.kt'", ui.description)
    }

    /** Verifies generic file Rename announces the matching-class update. */
    fun testSemanticFileRenameLabels() {
        val file = FileUtil.createMemoryFileSystem().root.createData("Before.kt")
        val ui = KotlinFilesystemRenameUI(
            KotlinRenameFileRefactoring(file, "After", updatePrimaryClass = true),
            false,
            "Before.kt",
        )

        assertEquals("Rename Kotlin File and Class", ui.name)
        assertTrue(ui.description, ui.description.contains("matching top-level class"))
    }
}
