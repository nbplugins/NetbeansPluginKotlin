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

/** Unit tests for [KotlinPackageRenameMapping]. */
class KotlinPackageRenameMappingTest : NbTestCase("KotlinPackageRenameMappingTest") {

    /** Verifies a directory directly below the root yields a one-segment package. */
    fun testRenamedPackage_flatPackage() {
        val root = root("src/main/kotlin")
        val directory = FileUtil.createFolder(root, "sample")
        val file = directory.createData("Sample.kt")

        assertEquals("renamed", rename(root, directory, "renamed", file))
    }

    /** Verifies a file in a nested directory keeps every descendant segment below the new name. */
    fun testRenamedPackage_keepsNestedDescendants() {
        val root = root("src/main/kotlin")
        val directory = FileUtil.createFolder(root, "sample")
        val file = FileUtil.createFolder(directory, "sub").createData("Nested.kt")

        assertEquals("renamed.sub", rename(root, directory, "renamed", file))
    }

    /** Verifies renaming a nested directory retains the package segments above it. */
    fun testRenamedPackage_keepsAncestorSegments() {
        val root = root("src/main/kotlin")
        val directory = FileUtil.createFolder(root, "com/sample")
        val file = FileUtil.createFolder(directory, "sub").createData("Nested.kt")

        assertEquals("com.renamed.sub", rename(root, directory, "renamed", file))
    }

    /** Verifies a file outside the renamed directory is rejected instead of silently misassigned. */
    fun testRenamedPackage_rejectsFileOutsideDirectory() {
        val root = root("src/main/kotlin")
        val directory = FileUtil.createFolder(root, "sample")
        val outside = FileUtil.createFolder(root, "other").createData("Other.kt")

        assertFailsWithMessage("not below") { rename(root, directory, "renamed", outside) }
    }

    /** Verifies a directory outside the supplied source root is rejected. */
    fun testRenamedPackage_rejectsDirectoryOutsideRoot() {
        val root = root("src/main/kotlin")
        val directory = root.parent.createFolder("outside")
        val file = directory.createData("Outside.kt")

        assertFailsWithMessage("not below") { rename(root, directory, "renamed", file) }
    }

    /** Verifies a path segment that cannot be a Kotlin package identifier is reported clearly. */
    fun testRenamedPackage_rejectsInvalidPathSegment() {
        val root = root("src/main/kotlin")
        val directory = FileUtil.createFolder(root, "sample")
        val file = FileUtil.createFolder(directory, "my-dir").createData("Invalid.kt")

        assertFailsWithMessage("'my-dir' is not a Kotlin identifier") {
            rename(root, directory, "renamed", file)
        }
    }

    /** Verifies an invalid target directory name is rejected before any filesystem change. */
    fun testRenamedPackage_rejectsInvalidNewName() {
        val root = root("src/main/kotlin")
        val directory = FileUtil.createFolder(root, "sample")
        val file = directory.createData("Sample.kt")

        assertFailsWithMessage("'1bad' is not a Kotlin identifier") { rename(root, directory, "1bad", file) }
    }

    /** Computes the post-rename package of [file] below [root]. */
    private fun rename(root: FileObject, directory: FileObject, newName: String, file: FileObject): String =
        KotlinPackageRenameMapping.renamedPackage(root, directory, newName, file)

    /** Creates a memory filesystem root at [path]. */
    private fun root(path: String): FileObject = FileUtil.createMemoryFileSystem().root.let {
        FileUtil.createFolder(it, path)
    }

    /** Asserts that [action] throws an [IllegalArgumentException] containing [expectedMessage]. */
    private fun assertFailsWithMessage(expectedMessage: String, action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalArgumentException containing '$expectedMessage'")
        } catch (error: IllegalArgumentException) {
            assertTrue(
                "Expected '$expectedMessage' in '${error.message}'",
                error.message.orEmpty().contains(expectedMessage),
            )
        }
    }
}
