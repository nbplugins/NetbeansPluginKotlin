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

import org.jetbrains.kotlin.builder.KotlinPsiManager
import org.jetbrains.kotlin.builder.isKotlinFile
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.spi.gototest.TestLocator
import org.openide.filesystems.FileObject

/**
 * [TestLocator] for Kotlin source files, enabling **Ctrl+Alt+T** ("Go to Test / Tested Class").
 *
 * Uses a naming convention: a file `Foo.kt` is paired with `FooTest.kt` (or `FooTests.kt`),
 * and vice versa. The search scans all Kotlin source files in the current project for a
 * matching name.
 *
 * Registered as a global service provider in `layer.xml` (under `Services/`).
 *
 * This class belongs to the **controller** layer.
 */
class KotlinTestLocator : TestLocator {

    /**
     * Returns `true` for `.kt` source files.
     *
     * @param fo the file to check
     * @return `true` if [fo] has the `kt` extension
     */
    override fun appliesTo(fo: FileObject): Boolean = fo.isKotlinFile()

    /**
     * Always returns `false` — this locator computes results synchronously in [findOpposite].
     *
     * @return `false`
     */
    override fun asynchronous(): Boolean = false

    /**
     * Classifies [fo] as a test file, a tested file, or neither.
     *
     * Files whose base name ends with `Test` or `Tests` are classified as [TestLocator.FileType.TEST];
     * all other `.kt` files are classified as [TestLocator.FileType.TESTED].
     *
     * @param fo the file to classify
     * @return [TestLocator.FileType.TEST], [TestLocator.FileType.TESTED], or
     *         [TestLocator.FileType.NEITHER]
     */
    override fun getFileType(fo: FileObject): TestLocator.FileType {
        if (!fo.isKotlinFile()) return TestLocator.FileType.NEITHER
        val baseName = fo.nameWithoutExtension
        return if (baseName.endsWith("Test") || baseName.endsWith("Tests")) {
            TestLocator.FileType.TEST
        } else {
            TestLocator.FileType.TESTED
        }
    }

    /**
     * Finds the test file for a source file, or the source file for a test file.
     *
     * For a source file `Foo.kt`, searches for `FooTest.kt` or `FooTests.kt`.
     * For a test file `FooTest.kt`, searches for `Foo.kt`.
     * The search scans all Kotlin files in the project via [KotlinPsiManager.getFilesByProject].
     *
     * @param fo the file whose opposite should be found
     * @param caretOffset the caret offset (unused; convention-based lookup does not need it)
     * @return a [TestLocator.LocationResult] pointing to the opposite file, or an error message
     */
    override fun findOpposite(fo: FileObject, caretOffset: Int): TestLocator.LocationResult {
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: return TestLocator.LocationResult("Cannot determine project for ${fo.path}")

        val baseName = fo.nameWithoutExtension
        val targetName: String = when (getFileType(fo)) {
            TestLocator.FileType.TEST -> {
                // FooTest.kt or FooTests.kt → look for Foo.kt
                when {
                    baseName.endsWith("Tests") -> baseName.removeSuffix("Tests")
                    else -> baseName.removeSuffix("Test")
                }
            }
            TestLocator.FileType.TESTED -> "${baseName}Test"
            else -> return TestLocator.LocationResult("Not a Kotlin source file")
        }

        val allFiles = KotlinPsiManager.getFilesByProject(project)
        val match = allFiles.firstOrNull { it.isKotlinFile() && it.nameWithoutExtension == targetName }
            ?: run {
                // Also try with 's' suffix for TEST→TESTED direction
                if (getFileType(fo) == TestLocator.FileType.TESTED) {
                    allFiles.firstOrNull { it.isKotlinFile() && it.nameWithoutExtension == "${baseName}Tests" }
                } else null
            }
            ?: return TestLocator.LocationResult("No opposite file found for ${fo.nameExt}")

        return TestLocator.LocationResult(match, -1)
    }

    /**
     * Not called because [asynchronous] returns `false`. Required by the [TestLocator] interface.
     *
     * @param fo the file object (unused)
     * @param caretOffset the caret offset (unused)
     * @param callback the callback (unused)
     */
    override fun findOpposite(fo: FileObject, caretOffset: Int, callback: TestLocator.LocationListener) {
        // synchronous locator — this method is never called
    }

    /** Returns the file name without the `.kt` extension. */
    private val FileObject.nameWithoutExtension: String
        get() = nameExt.removeSuffix(".kt").removeSuffix(".KT")
}
