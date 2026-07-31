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
package io.github.nbplugins.kotlin.nbm.resolve

import org.netbeans.junit.NbTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** Tests build-root sibling-module selection for K2 refactorings. */
class BuildProjectScopeTest : NbTestCase("BuildProjectScopeTest") {
    /** Includes Maven reactor modules but excludes a project outside the reactor root. */
    fun testRelatedProjectPaths_mavenReactor_includesSiblingModulesOnly() {
        val root = Files.createTempDirectory("kotlin-maven-reactor")
        val api = root.resolve("api")
        val implementation = root.resolve("implementation")
        val unrelated = Files.createTempDirectory("kotlin-unrelated")
        try {
            root.resolve("pom.xml").writeText("<project/>")
            Files.createDirectories(api)
            Files.createDirectories(implementation)
            api.resolve("pom.xml").writeText("<project/>")
            implementation.resolve("pom.xml").writeText("<project/>")
            unrelated.resolve("pom.xml").writeText("<project/>")

            val result = BuildProjectScope.relatedProjectPaths(
                ownerPath = api,
                candidatePaths = listOf(api, implementation, unrelated),
                buildKind = BuildProjectScope.BuildKind.MAVEN,
            )

            assertEquals(setOf(api, implementation), result.toSet())
        } finally {
            root.toFile().deleteRecursively()
            unrelated.toFile().deleteRecursively()
        }
    }

    /** Includes Gradle sibling modules sharing settings.gradle.kts but excludes another build. */
    fun testRelatedProjectPaths_gradleBuild_includesSiblingModulesOnly() {
        val root = Files.createTempDirectory("kotlin-gradle-build")
        val app = root.resolve("app")
        val library = root.resolve("library")
        val unrelated = Files.createTempDirectory("kotlin-unrelated-gradle")
        try {
            root.resolve("settings.gradle.kts").writeText("include(\":app\", \":library\")")
            Files.createDirectories(app)
            Files.createDirectories(library)
            unrelated.resolve("settings.gradle").writeText("")

            val result = BuildProjectScope.relatedProjectPaths(
                ownerPath = app,
                candidatePaths = listOf(app, library, unrelated),
                buildKind = BuildProjectScope.BuildKind.GRADLE,
            )

            assertEquals(setOf(app, library), result.toSet())
        } finally {
            root.toFile().deleteRecursively()
            unrelated.toFile().deleteRecursively()
        }
    }

    /** Leaves an Ant/J2SE project isolated because it has no multi-module build model. */
    fun testRelatedProjectPaths_standalone_returnsOwnerOnly() {
        val owner = Files.createTempDirectory("kotlin-standalone")
        val sibling = Files.createTempDirectory("kotlin-sibling")
        try {
            val result = BuildProjectScope.relatedProjectPaths(
                ownerPath = owner,
                candidatePaths = listOf(owner, sibling),
                buildKind = BuildProjectScope.BuildKind.STANDALONE,
            )

            assertEquals(listOf(owner), result)
        } finally {
            owner.toFile().deleteRecursively()
            sibling.toFile().deleteRecursively()
        }
    }
}
