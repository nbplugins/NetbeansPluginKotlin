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

/** Unit tests for [KotlinChangePackageSourceSelection]. */
class KotlinChangePackageSourceSelectionTest : NbTestCase("KotlinChangePackageSourceSelectionTest") {

    /** Verifies a folder selection includes only Kotlin descendants in deterministic path order. */
    fun testFrom_directoryCollectsOnlyKotlinFilesInDeterministicOrder() {
        val root = FileUtil.createMemoryFileSystem().root
        val feature = root.createFolder("feature")
        feature.createData("Readme.txt")
        val api = feature.createFolder("api")
        val apiFile = api.createData("Api.kt")
        val internal = feature.createFolder("internal")
        val helper = internal.createData("Helper.kt")

        val selection = KotlinChangePackageSourceSelection.from(feature) ?: error("Expected Kotlin selection")

        assertTrue(selection.isDirectory)
        assertEquals("feature", selection.displayName)
        assertEquals(listOf(apiFile.path, helper.path), selection.sourceFiles.map(FileObject::getPath))
    }

    /** Verifies nested descendants retain only their relative folder hierarchy below the target package. */
    fun testTargetRelativePackageSegments_directoryExcludesSelectedFolderName() {
        val root = FileUtil.createMemoryFileSystem().root
        val feature = root.createFolder("feature")
        val internal = feature.createFolder("internal")
        val helper = internal.createData("Helper.kt")
        val rootFile = feature.createData("Public.kt")
        val selection = KotlinChangePackageSourceSelection.from(feature) ?: error("Expected Kotlin selection")

        assertEquals(listOf("internal"), selection.targetRelativePackageSegments(helper))
        assertEquals(emptyList<String>(), selection.targetRelativePackageSegments(rootFile))
    }

    /** Verifies an editor file selection has no extra target package hierarchy. */
    fun testFrom_fileCreatesSingleFileSelectionWithoutRelativeHierarchy() {
        val root = FileUtil.createMemoryFileSystem().root
        val file = root.createData("Single.kt")

        val selection = KotlinChangePackageSourceSelection.from(file) ?: error("Expected Kotlin selection")

        assertFalse(selection.isDirectory)
        assertEquals(listOf(file), selection.sourceFiles)
        assertEquals(emptyList<String>(), selection.targetRelativePackageSegments(file))
    }

    /** Verifies non-Kotlin files and folders without Kotlin descendants are rejected. */
    fun testFrom_nonKotlinSourceReturnsNull() {
        val root = FileUtil.createMemoryFileSystem().root
        val resources = root.createFolder("resources")
        resources.createData("config.json")
        val text = root.createData("Notes.txt")

        assertNull(KotlinChangePackageSourceSelection.from(resources))
        assertNull(KotlinChangePackageSourceSelection.from(text))
    }
}
