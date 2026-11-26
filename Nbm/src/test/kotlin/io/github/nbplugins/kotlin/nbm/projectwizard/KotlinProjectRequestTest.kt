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
package io.github.nbplugins.kotlin.nbm.projectwizard

import org.jetbrains.kotlin.tools.projectWizard.plugins.buildSystem.BuildSystemType
import org.netbeans.junit.NbTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [KotlinProjectRequest] validation and its identifier helpers.
 */
class KotlinProjectRequestTest : NbTestCase("KotlinProjectRequestTest") {

    private lateinit var directory: Path

    override fun setUp() {
        super.setUp()
        directory = getWorkDir().toPath().resolve("project")
    }

    private fun request(
        name: String = "demo",
        groupId: String = "org.example",
        artifactId: String = "demo",
        version: String = "1.0-SNAPSHOT",
        buildSystem: BuildSystemType = BuildSystemType.GradleKotlinDsl,
        variant: ProjectVariant = ProjectVariant.APPLICATION,
    ) = KotlinProjectRequest(directory, name, groupId, artifactId, version, buildSystem, variant)

    /** A well-formed request with a fresh directory must validate without problems. */
    fun testValidRequestHasNoProblems() {
        val validation = request().validate()
        assertTrue(validation.isValid)
        assertTrue(validation.errors.isEmpty())
        assertEquals(KotlinProjectProblem.DIRECTORY_NOT_EMPTY in validation.warnings, false)
    }

    /** Blank identifiers are reported per field. */
    fun testBlankFieldsAreReported() {
        val validation = request(name = " ", groupId = " ", artifactId = "", version = " ").validate()
        assertFalse(validation.isValid)
        assertTrue(KotlinProjectProblem.EMPTY_NAME in validation.errors)
        assertTrue(KotlinProjectProblem.EMPTY_GROUP_ID in validation.errors)
        assertTrue(KotlinProjectProblem.EMPTY_ARTIFACT_ID in validation.errors)
        assertTrue(KotlinProjectProblem.EMPTY_VERSION in validation.errors)
    }

    /** A slash or a colon cannot appear in a module identifier. */
    fun testIllegalCharactersAreReported() {
        val validation = request(name = "my/project", groupId = "org:example", artifactId = "a\"b", version = "1.0;2").validate()
        assertTrue(KotlinProjectProblem.INVALID_NAME in validation.errors)
        assertTrue(KotlinProjectProblem.INVALID_GROUP_ID in validation.errors)
        assertTrue(KotlinProjectProblem.INVALID_ARTIFACT_ID in validation.errors)
        assertTrue(KotlinProjectProblem.INVALID_VERSION in validation.errors)
    }

    /** Names may contain letters, digits, space, `-`, `_` and `.`, like IDEA allows. */
    fun testModuleIdentifierAcceptsIdEaRules() {
        assertTrue(KotlinProjectRequest.isModuleIdentifier("my project-1.2_A"))
        assertTrue(KotlinProjectRequest.isModuleIdentifier("Проект"))
        assertFalse(KotlinProjectRequest.isModuleIdentifier("my/project"))
        assertFalse(KotlinProjectRequest.isModuleIdentifier(""))
        assertFalse(KotlinProjectRequest.isModuleIdentifier("   "))
    }

    /** Versions may contain letters, digits, `-`, `_` and `.`, but no spaces. */
    fun testCoordinateIdentifierAcceptsIdEaRules() {
        assertTrue(KotlinProjectRequest.isCoordinateIdentifier("org.example-1.0_A"))
        assertFalse(KotlinProjectRequest.isCoordinateIdentifier("org example"))
        assertFalse(KotlinProjectRequest.isCoordinateIdentifier(""))
    }

    /** Maven rejects artifact IDs with spaces or non-latin letters. */
    fun testArtifactIdFollowsMavenRules() {
        assertTrue(KotlinProjectRequest.isArtifactId("my-app_1.0"))
        assertFalse(KotlinProjectRequest.isArtifactId("my app"))
        assertFalse(KotlinProjectRequest.isArtifactId("Проект"))
        assertFalse(KotlinProjectRequest.isArtifactId("a/b"))
        assertFalse(KotlinProjectRequest.isArtifactId(""))
    }

    /** Maven applies the same identifier rules to a group ID. */
    fun testGroupIdFollowsMavenRules() {
        assertTrue(KotlinProjectRequest.isGroupId("org.example-1.0_A"))
        assertFalse(KotlinProjectRequest.isGroupId("org.проект"))
        assertFalse(KotlinProjectRequest.isGroupId("org example"))
        assertFalse(KotlinProjectRequest.isGroupId(""))

        val validation = request(groupId = "org.проект").validate()
        assertTrue(KotlinProjectProblem.INVALID_GROUP_ID in validation.errors)
    }

    /** An artifact ID Maven cannot use is an error even though it is a legal project name. */
    fun testMavenIncompatibleArtifactIdIsAnError() {
        val validation = request(name = "my app", artifactId = "my app").validate()

        assertTrue(KotlinProjectProblem.INVALID_ARTIFACT_ID in validation.errors)
        assertFalse(KotlinProjectProblem.INVALID_NAME in validation.errors)
    }

    /** A non-latin project name is still a legal project name; only its ID needs manual input. */
    fun testNonLatinNameIsValid() {
        val validation = request(name = "Проект").validate()

        assertTrue(KotlinProjectProblem.INVALID_NAME !in validation.errors)
    }

    /** An existing non-empty directory is a warning, not an error. */
    fun testNonEmptyDirectoryIsOnlyAWarning() {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("existing.txt"), "x")

        val validation = request().validate()

        assertTrue("non-empty directory must not block generation", validation.isValid)
        assertEquals(listOf(KotlinProjectProblem.DIRECTORY_NOT_EMPTY), validation.warnings)
    }

    /** An existing empty directory is fine and produces no warning. */
    fun testEmptyDirectoryHasNoWarning() {
        Files.createDirectories(directory)
        assertTrue(request().validate().warnings.isEmpty())
    }
}
