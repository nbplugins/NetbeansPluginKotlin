/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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

import utils.KotlinTestCase

/** Unit tests for Extract Super source-root and target-package resolution. */
class KotlinPackageTargetTest : KotlinTestCase("KotlinPackageTargetTest", "extractSuper") {
    /** Creates a nested target package below the selected root. */
    fun testResolveDirectory_createsNestedTargetPackage() {
        val source = dir.getFileObject("simple")?.getFileObject("file.kt") ?: return
        val target = KotlinPackageTarget(project, source)
        val rootPath = target.defaultRootPath ?: return

        val directory = target.resolveDirectory(rootPath, "generated.api")

        assertNotNull("Expected selected package directory", directory)
        assertEquals("generated/api", directory!!.path.removePrefix("${targetRoot(rootPath).path}/"))
    }

    /** Rejects package names with empty or invalid identifier segments. */
    fun testIsValidPackage_rejectsInvalidSegments() {
        val source = dir.getFileObject("simple")?.getFileObject("file.kt") ?: return
        val target = KotlinPackageTarget(project, source)

        assertTrue(target.isValidPackage(""))
        assertTrue(target.isValidPackage("sample.generated"))
        assertFalse(target.isValidPackage("sample..generated"))
        assertFalse(target.isValidPackage("sample.bad-name"))
    }

    /** Looks up the source-root object selected by the model. */
    private fun targetRoot(path: String) = KotlinPackageTarget(
        project,
        dir.getFileObject("simple")!!.getFileObject("file.kt")!!,
    ).roots.first { root -> root.path == path }.folder
}
