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

/** Unit tests for [KotlinChangePackageRefactoring]'s plugin-factory applicability. */
class KotlinChangePackageRefactoringTest : NbTestCase("KotlinChangePackageRefactoringTest") {

    /** Verifies a folder carrier creates the plugin without requiring an editor document. */
    fun testFactory_createsPluginForDirectorySelectionWithoutStyledDocument() {
        val root = FileUtil.createMemoryFileSystem().root
        val folder = root.createFolder("feature")
        folder.createData("Public.kt")
        val selection = KotlinChangePackageSourceSelection.from(folder) ?: error("Expected Kotlin selection")
        val refactoring = KotlinChangePackageRefactoring(selection)

        val plugin = KotlinRefactoringsFactory().createInstance(refactoring)

        assertTrue(
            "Change Package folder selection must not be rejected by the editor-document applicability guard",
            plugin is KotlinChangePackagePlugin,
        )
    }
}
