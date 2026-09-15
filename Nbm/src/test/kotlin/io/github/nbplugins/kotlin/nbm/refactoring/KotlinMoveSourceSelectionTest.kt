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

/** Unit tests for [KotlinMoveSourceSelection]. */
class KotlinMoveSourceSelectionTest : NbTestCase("KotlinMoveSourceSelectionTest") {

    /** Verifies a selected directory recursively includes only Kotlin source files in path order. */
    fun testFrom_directoryCollectsOnlyKotlinFilesInDeterministicOrder() {
        val root = FileUtil.createMemoryFileSystem().root
        val feature = root.createFolder("feature")
        feature.createData("Readme.txt")
        val api = feature.createFolder("api")
        val apiFile = api.createData("Api.kt")
        val internal = feature.createFolder("internal")
        val helper = internal.createData("Helper.kt")
        feature.createFolder("empty")

        val selection = KotlinMoveSourceSelection.from(feature) ?: error("Expected Kotlin directory selection")

        assertTrue(selection.isDirectory)
        assertEquals("feature", selection.displayName)
        assertEquals(listOf(apiFile.path, helper.path), selection.sourceFiles.map(FileObject::getPath))
    }

    /** Verifies a directory target retains its own name and each nested relative parent path. */
    fun testTargetRelativeFolderSegments_directoryRetainsDirectoryAndNestedParent() {
        val root = FileUtil.createMemoryFileSystem().root
        val feature = root.createFolder("feature")
        val internal = feature.createFolder("internal")
        val helper = internal.createData("Helper.kt")
        val rootFile = feature.createData("Public.kt")
        val selection = KotlinMoveSourceSelection.from(feature) ?: error("Expected selection")

        assertEquals(listOf("feature", "internal"), selection.targetRelativeFolderSegments(helper))
        assertEquals(listOf("feature"), selection.targetRelativeFolderSegments(rootFile))
    }

    /** Verifies a direct Kotlin-file selection has no retained directory hierarchy. */
    fun testFrom_fileCreatesSingleFileSelectionWithoutRelativeFolder() {
        val root = FileUtil.createMemoryFileSystem().root
        val file = root.createData("Single.kt")

        val selection = KotlinMoveSourceSelection.from(file) ?: error("Expected Kotlin file selection")

        assertFalse(selection.isDirectory)
        assertEquals(listOf(file), selection.sourceFiles)
        assertEquals(emptyList<String>(), selection.targetRelativeFolderSegments(file))
    }

    /** Verifies folders without Kotlin descendants and non-Kotlin files cannot start the refactoring. */
    fun testFrom_nonKotlinSourceReturnsNull() {
        val root = FileUtil.createMemoryFileSystem().root
        val folder = root.createFolder("resources")
        folder.createData("config.json")
        val text = root.createData("Notes.txt")

        assertNull(KotlinMoveSourceSelection.from(folder))
        assertNull(KotlinMoveSourceSelection.from(text))
    }
}
