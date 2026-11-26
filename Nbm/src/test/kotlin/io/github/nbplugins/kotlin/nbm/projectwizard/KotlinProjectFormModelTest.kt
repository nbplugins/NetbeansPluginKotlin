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
 * Tests for [KotlinProjectFormModel]: default values, artifact ID derivation and validation
 * exposed to the wizard panel.
 */
class KotlinProjectFormModelTest : NbTestCase("KotlinProjectFormModelTest") {

    /** Each test starts with an empty work directory. */
    override fun setUp() {
        super.setUp()
        clearWorkDir()
    }

    /** Every variant starts with its own readable project name and a legal artifact ID. */
    fun testDefaultsPerVariant() {
        val expectedNames = mapOf(
            ProjectVariant.APPLICATION to "KotlinApplication",
            ProjectVariant.LIBRARY to "KotlinLibrary",
            ProjectVariant.SCRIPT to "KotlinScript",
        )
        for ((variant, expectedName) in expectedNames) {
            val model = modelInWorkDir(variant)

            assertEquals(expectedName, model.projectName)
            assertEquals(expectedName, model.artifactId)
            assertEquals(KotlinProjectFormModel.DEFAULT_GROUP_ID, model.groupId)
            assertEquals(KotlinProjectFormModel.DEFAULT_VERSION, model.version)
            assertEquals(BuildSystemType.GradleKotlinDsl, model.buildSystem)
            assertTrue("default location must not be blank", model.location.isNotBlank())
        }
    }

    /** The artifact ID follows the project name until the user customizes it. */
    fun testArtifactIdFollowsProjectName() {
        val model = modelInWorkDir(ProjectVariant.APPLICATION)

        model.projectName = "My Demo App"
        assertEquals("MyDemoApp", model.artifactId)

        model.artifactId = "custom"
        model.projectName = "Another Name"
        assertEquals("the custom artifact ID must survive a name change", "custom", model.artifactId)
    }

    /** Spaces, punctuation and non-latin letters are removed when deriving an artifact ID. */
    fun testArtifactIdForRemovesIllegalCharacters() {
        assertEquals("my-app_1.0", KotlinProjectFormModel.artifactIdFor("my-app_1.0"))
        assertEquals("MyApp", KotlinProjectFormModel.artifactIdFor("My App!"))
        assertEquals("", KotlinProjectFormModel.artifactIdFor("  "))
        assertEquals("", KotlinProjectFormModel.artifactIdFor("Проект"))
    }

    /** The project directory is the location directory plus the project name. */
    fun testProjectDirectoryIsLocationPlusName() {
        val model = modelInWorkDir(ProjectVariant.LIBRARY)
        model.location = "/tmp/projects"
        model.projectName = "demo"

        assertEquals(Path.of("/tmp/projects/demo"), model.projectDirectory)
    }

    /**
     * A location with redundant `.` components is normalized, because
     * `FileUtil.toFileObject` rejects such a path when the project is opened.
     */
    fun testProjectDirectoryIsNormalized() {
        val model = modelInWorkDir(ProjectVariant.LIBRARY)
        model.location = "/tmp/."
        model.projectName = "demo"

        assertEquals(Path.of("/tmp/demo"), model.projectDirectory)
    }

    /** A blank location falls back to the default projects location instead of throwing. */
    fun testBlankLocationFallsBackToDefault() {
        val model = modelInWorkDir(ProjectVariant.LIBRARY)
        model.location = "  "
        model.projectName = "demo"

        assertEquals(Path.of(KotlinProjectFormModel.defaultProjectsLocation()).resolve("demo"), model.projectDirectory)
    }

    /** Valid defaults produce a valid request carrying the variant and the chosen build system. */
    fun testToRequestReturnsEnteredValues() {
        val model = modelInWorkDir(ProjectVariant.SCRIPT)
        model.projectName = "demo"
        model.buildSystem = BuildSystemType.Maven
        model.groupId = "com.example"
        model.version = "0.1.0"

        val request = model.toRequest()

        assertEquals(model.projectDirectory, request.directory)
        assertEquals("demo", request.name)
        assertEquals("com.example", request.groupId)
        assertEquals("demo", request.artifactId)
        assertEquals("0.1.0", request.version)
        assertEquals(BuildSystemType.Maven, request.buildSystem)
        assertEquals(ProjectVariant.SCRIPT, request.variant)
        assertTrue(model.isValid())
    }

    /** Blank identifiers are errors and block generation. */
    fun testBlankFieldsAreErrors() {
        val model = modelInWorkDir(ProjectVariant.APPLICATION)
        model.projectName = ""
        model.groupId = ""
        model.artifactId = ""
        model.version = ""

        val validation = model.validation()

        assertEquals(
            listOf(
                KotlinProjectProblem.EMPTY_NAME,
                KotlinProjectProblem.EMPTY_GROUP_ID,
                KotlinProjectProblem.EMPTY_ARTIFACT_ID,
                KotlinProjectProblem.EMPTY_VERSION,
            ),
            validation.errors,
        )
        assertFalse(model.isValid())
    }

    /** Characters outside the IDEA rules are errors. */
    fun testIllegalCharactersAreErrors() {
        val model = modelInWorkDir(ProjectVariant.APPLICATION)
        model.projectName = "bad/name"
        model.groupId = "com.example/test"
        model.artifactId = "bad/artifact"
        model.version = "1.0+"

        assertEquals(
            listOf(
                KotlinProjectProblem.INVALID_NAME,
                KotlinProjectProblem.INVALID_GROUP_ID,
                KotlinProjectProblem.INVALID_ARTIFACT_ID,
                KotlinProjectProblem.INVALID_VERSION,
            ),
            model.validation().errors,
        )
    }

    /**
     * A name that cannot be part of a path on this platform is reported as an invalid name
     * instead of throwing from the document listener that calls validation.
     */
    fun testUnparsableNameIsReportedAsInvalidName() {
        val model = modelInWorkDir(ProjectVariant.APPLICATION)
        model.projectName = UNPARSABLE

        val validation = model.validation()

        assertEquals(listOf(KotlinProjectProblem.INVALID_NAME), validation.errors)
        assertFalse(model.isValid())
    }

    /** An unparsable location is reported as an invalid location. */
    fun testUnparsableLocationIsReportedAsInvalidLocation() {
        val model = modelInWorkDir(ProjectVariant.APPLICATION)
        model.location = UNPARSABLE

        val validation = model.validation()

        assertEquals(listOf(KotlinProjectProblem.INVALID_LOCATION), validation.errors)
        assertFalse(model.isValid())
    }

    /** A non-empty target directory is only a warning: generation remains possible. */
    fun testNonEmptyDirectoryIsWarningOnly() {
        val model = modelInWorkDir(ProjectVariant.LIBRARY)
        model.projectName = "existing"
        Files.createDirectories(model.projectDirectory)
        Files.createFile(model.projectDirectory.resolve("keep.txt"))

        val validation = model.validation()

        assertEquals(emptyList<KotlinProjectProblem>(), validation.errors)
        assertEquals(listOf(KotlinProjectProblem.DIRECTORY_NOT_EMPTY), validation.warnings)
        assertTrue(model.isValid())
    }

    /** A target path that is a regular file is an error: the project cannot be created there. */
    fun testFileTargetIsAnError() {
        val model = modelInWorkDir(ProjectVariant.LIBRARY)
        model.projectName = "occupied"
        Files.createFile(model.projectDirectory)

        assertEquals(listOf(KotlinProjectProblem.PATH_NOT_DIRECTORY), model.validation().errors)
        assertFalse(model.isValid())
    }

    /** An empty target directory produces neither errors nor warnings. */
    fun testEmptyDirectoryIsAccepted() {
        val model = modelInWorkDir(ProjectVariant.LIBRARY)
        model.projectName = "empty"
        Files.createDirectories(model.projectDirectory)

        assertEquals(KotlinProjectValidation.OK, model.validation())
    }

    /**
     * A directory that cannot be listed is an error rather than an exception: validation runs
     * from swing document listeners, where an escaped `AccessDeniedException` would leave the
     * panel state stale.
     */
    fun testUnreadableDirectoryIsAnError() {
        val model = modelInWorkDir(ProjectVariant.LIBRARY)
        model.projectName = "unreadable"
        val directory = model.projectDirectory
        Files.createDirectories(directory)
        val originalPermissions = Files.getPosixFilePermissions(directory)
        try {
            Files.setPosixFilePermissions(directory, emptySet())
            // Root ignores permission bits; there is nothing to assert in that case.
            if (Files.isReadable(directory)) return

            assertEquals(listOf(KotlinProjectProblem.DIRECTORY_NOT_READABLE), model.validation().errors)
            assertFalse(model.isValid())
        } finally {
            Files.setPosixFilePermissions(directory, originalPermissions)
        }
    }

    /** Creates a model whose location is the test work directory. */
    private fun modelInWorkDir(variant: ProjectVariant): KotlinProjectFormModel =
        KotlinProjectFormModel(variant).apply { location = getWorkDir().absolutePath }

    private companion object {
        /** A value no platform accepts as part of a file name: it contains a NUL character. */
        const val UNPARSABLE = "bad\u0000name"
    }
}
