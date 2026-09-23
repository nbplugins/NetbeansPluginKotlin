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
import java.nio.file.Files
import java.nio.file.Path

/** Unit tests for [KotlinChangePackageDirectoryAction]. */
class KotlinChangePackageDirectoryActionTest : NbTestCase("KotlinChangePackageDirectoryActionTest") {

    /** Verifies source discovery can run outside the Event Dispatch Thread before UI marshalling. */
    fun testAsynchronous_allowsBackgroundSourceDiscovery() {
        assertTrue(
            "K2 source discovery may run in NetBeans' RequestProcessor before UI is marshalled to EDT",
            TestAction().isAsynchronous(),
        )
    }

    /** Verifies the directory action has exactly one folder-context layer registration. */
    fun testLayer_registersFolderActionWithoutGlobalMenuDuplicate() {
        val layer = repositoryRoot().resolve("Nbm/src/main/resources/org/jetbrains/kotlin/layer.xml")
        val contents = Files.readString(layer)
        val actionName = "KotlinChangePackageDirectoryAction.shadow"

        assertEquals("Folder context action must have exactly one dedicated layer shadow", 1, contents.countOccurrences(actionName))
        assertTrue("Folder context action must be registered in Loaders/folder/any/Actions", contents.contains("<folder name=\"folder\">"))
    }

    /** Finds the checkout root from the NBM module's Surefire working directory. */
    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml")) && Files.isDirectory(candidate.resolve("Nbm"))) {
                return candidate
            }
            candidate = candidate.parent
        }
        fail("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
        error("unreachable")
    }

    /** Counts non-overlapping literal [needle] occurrences in this source text. */
    private fun String.countOccurrences(needle: String): Int =
        split(needle).size - 1

    /** Exposes NodeAction's protected dispatcher contract for this focused test. */
    private class TestAction : KotlinChangePackageDirectoryAction() {
        fun isAsynchronous(): Boolean = asynchronous()
    }
}
