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

/** Unit tests for [KotlinImportPackageTarget]. */
class KotlinImportPackageTargetTest : NbTestCase("KotlinImportPackageTargetTest") {

    /** Verifies a declaration import offers every enclosing package, longest first. */
    fun testCandidates_declarationImportListsLongestPackageFirst() {
        assertEquals(
            listOf("a.b.c", "a.b", "a"),
            KotlinImportPackageTarget.candidates("a.b.c.Widget", allUnder = false),
        )
    }

    /** Verifies a star import names exactly one package and never falls back to its parents. */
    fun testCandidates_starImportUsesWholePathOnly() {
        assertEquals(
            listOf("a.b.c"),
            KotlinImportPackageTarget.candidates("a.b.c", allUnder = true),
        )
    }

    /** Verifies single-segment paths only name a package when the directive is a star import. */
    fun testCandidates_singleSegmentPathHasNoDeclarationCandidate() {
        assertEquals(emptyList<String>(), KotlinImportPackageTarget.candidates("Widget", allUnder = false))
        assertEquals(listOf("Widget"), KotlinImportPackageTarget.candidates("Widget", allUnder = true))
    }

    /** Verifies nested class imports still resolve to the closest package declared by sources. */
    fun testResolve_prefersLongestDeclaredPackage() {
        val root = FileUtil.createMemoryFileSystem().root
        val outer = root.createData("Outer.kt")

        val target = KotlinImportPackageTarget.resolve("a.b.Outer.Inner", allUnder = false) { packageName ->
            if (packageName == "a.b") listOf(outer) else emptyList()
        }

        assertEquals(KotlinImportPackageTarget.Declared("a.b", listOf(outer)), target)
    }

    /** Verifies an undeclared longest prefix does not stop the search for a shorter package. */
    fun testResolve_skipsPackagesWithoutSources() {
        val root = FileUtil.createMemoryFileSystem().root
        val declared = root.createData("Widget.kt")

        val target = KotlinImportPackageTarget.resolve("a.b.c.Widget", allUnder = false) { packageName ->
            if (packageName == "a.b") listOf(declared) else emptyList()
        }

        assertEquals(KotlinImportPackageTarget.Declared("a.b", listOf(declared)), target)
    }

    /** Verifies an import of a library package reports the imported package as external. */
    fun testResolve_reportsExternalLibraryPackage() {
        val target = KotlinImportPackageTarget.resolve("kotlin.collections.List", allUnder = false) { emptyList() }

        assertEquals(KotlinImportPackageTarget.External("kotlin.collections"), target)
    }

    /** Verifies an external star import keeps the whole path as the reported package. */
    fun testResolve_reportsExternalStarPackage() {
        val target = KotlinImportPackageTarget.resolve("kotlin.collections", allUnder = true) { emptyList() }

        assertEquals(KotlinImportPackageTarget.External("kotlin.collections"), target)
    }

    /** Verifies a blank import path cannot produce a package target. */
    fun testResolve_rejectsBlankImportPath() {
        assertNull(KotlinImportPackageTarget.resolve("", allUnder = false) { emptyList() })
        assertNull(KotlinImportPackageTarget.resolve("  ", allUnder = true) { emptyList() })
    }

    /** Verifies a declared package keeps the file list that the refactoring will change. */
    fun testResolve_declaredTargetKeepsFiles() {
        val root = FileUtil.createMemoryFileSystem().root
        val widget: FileObject = root.createData("Widget.kt")

        val target = KotlinImportPackageTarget.resolve("a.b.Widget", allUnder = false) { listOf(widget) }

        assertEquals(KotlinImportPackageTarget.Declared("a.b", listOf(widget)), target)
    }
}
