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

import java.nio.file.Files
import java.nio.file.Path
import org.netbeans.junit.NbTestCase

/** Unit tests for [KotlinRenameDirectoryAction]. */
class KotlinRenameDirectoryActionTest : NbTestCase("KotlinRenameDirectoryActionTest") {

    /** Verifies the legacy NodeAction dispatcher contract is explicitly synchronous. */
    fun testAsynchronous_isExplicitlyFalse() {
        assertFalse(
            "The directory action must declare its dispatcher contract to NetBeans",
            TestAction().isAsynchronous(),
        )
    }

    /** Verifies the Refactor menu routes a package node through the node-aware package action. */
    fun testLayer_routesPackageNodeActionThroughRefactorMenu() {
        val layer = repositoryRoot().resolve("Nbm/src/main/resources/org/jetbrains/kotlin/layer.xml")
        val contents = Files.readString(layer)

        assertTrue(
            "The directory package action must be present in Menu/Refactoring for a package-node selection",
            contents.contains("io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangePackageDirectoryAction.shadow"),
        )
        assertFalse(
            "The editor-only package action must not be a Refactor-menu shadow",
            contents.contains("<file name=\"io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangePackageAction.shadow\">"),
        )
        assertTrue(
            "The directory package action must remain globally registered for the Refactor submenu",
            contents.contains("io-github-nbplugins-kotlin-nbm-refactoring-KotlinChangePackageDirectoryAction.instance"),
        )
        val provider = "org-jetbrains-kotlin-refactorings-rename-KotlinActionsImplementationProvider.instance"
        val providerStart = contents.indexOf("<file name=\"$provider\">")
        val providerEnd = contents.indexOf("</file>", providerStart)
        assertTrue("The existing Kotlin rename provider must remain registered", providerStart >= 0)
        assertTrue("The Kotlin rename provider must precede Java's generic position-100 provider",
            contents.substring(providerStart, providerEnd).contains("intvalue=\"99\""))
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

    /** Exposes NodeAction's protected dispatcher contract for this focused test. */
    private class TestAction : KotlinRenameDirectoryAction() {
        fun isAsynchronous(): Boolean = asynchronous()
    }
}
