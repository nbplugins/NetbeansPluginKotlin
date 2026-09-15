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
import org.jetbrains.kotlin.refactorings.rename.KotlinRefactoringsFactory

/** Unit tests for [KotlinMoveFileRefactoring]'s plugin-factory applicability. */
class KotlinMoveFileRefactoringTest : NbTestCase("KotlinMoveFileRefactoringTest") {

    /** Verifies folder-context sources create the Kotlin Move File plugin without an editor document. */
    fun testFactory_createsPluginForDirectorySelectionWithoutStyledDocument() {
        val root = FileUtil.createMemoryFileSystem().root
        val folder = root.createFolder("feature")
        folder.createData("Public.kt")
        val selection = KotlinMoveSourceSelection.from(folder) ?: error("Expected Kotlin selection")
        val refactoring = KotlinMoveFileRefactoring(selection)

        val plugin = KotlinRefactoringsFactory().createInstance(refactoring)

        assertTrue("Move Directory must not be rejected by the editor-document applicability guard", plugin is KotlinMoveFilePlugin)
    }
}
