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

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome
import org.jetbrains.kotlin.tools.projectWizard.plugins.buildSystem.BuildSystemType
import org.netbeans.junit.NbTestCase
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end tests for [KotlinProjectGenerator]: every supported variant × build system is
 * generated into the test work directory and inspected on disk.
 */
class KotlinProjectGeneratorTest : NbTestCase("KotlinProjectGeneratorTest") {

    private val generator = KotlinProjectGenerator()
    private val buildSystems = listOf(
        BuildSystemType.GradleKotlinDsl,
        BuildSystemType.GradleGroovyDsl,
        BuildSystemType.Maven,
    )

    override fun setUp() {
        super.setUp()
        // The work directory is shared by all test methods of the class and generation never
        // overwrites existing files, so leftover projects would hide real results.
        clearWorkDir()
        FakeIntellijHome.startUp()
        KotlinAnalysisAPISession.initApplicationEnvironment()
        ProjectWizardEnvironment.ensureRegistered()
    }

    private fun request(
        variant: ProjectVariant,
        buildSystem: BuildSystemType,
        name: String = "demo",
    ): KotlinProjectRequest {
        val directory = getWorkDir().toPath().resolve("${variant.name.lowercase()}-${buildSystem.id}")
        return KotlinProjectRequest(
            directory = directory,
            name = name,
            groupId = "org.example",
            artifactId = name,
            version = "1.0-SNAPSHOT",
            buildSystem = buildSystem,
            variant = variant,
        )
    }

    private fun buildFileName(buildSystem: BuildSystemType) = when (buildSystem) {
        BuildSystemType.GradleKotlinDsl -> "build.gradle.kts"
        BuildSystemType.GradleGroovyDsl -> "build.gradle"
        BuildSystemType.Maven -> "pom.xml"
        else -> error("unsupported build system $buildSystem")
    }

    /** Every variant × build system combination produces the expected project skeleton. */
    fun testGeneratesAllVariantsForAllBuildSystems() {
        for (variant in ProjectVariant.values()) {
            for (buildSystem in buildSystems) {
                val request = request(variant, buildSystem)
                val progress = mutableListOf<String>()

                val root = generator.generate(request, onProgress = { progress += it })

                assertEquals("the generator must return the project directory", request.directory, root)
                assertTrue("$variant/$buildSystem must report progress", progress.isNotEmpty())
                assertGeneratedProject(request, variant, buildSystem)
            }
        }
    }

    /**
     * Asserts the generated project layout.
     */
    private fun assertGeneratedProject(
        request: KotlinProjectRequest,
        variant: ProjectVariant,
        buildSystem: BuildSystemType,
    ) {
        val root = request.directory
        val buildFile = root.resolve(buildFileName(buildSystem))
        assertTrue("$buildFile must exist", Files.isRegularFile(buildFile))
        assertTrue("build file must reference Kotlin $GENERATED_KOTLIN_VERSION", Files.readString(buildFile).contains(GENERATED_KOTLIN_VERSION))
        assertTrue("src/main/kotlin must exist", Files.isDirectory(root.resolve("src/main/kotlin")))

        assertTrue("Main.kt must exist for the application variant", Files.isRegularFile(root.resolve("src/main/kotlin/Main.kt")) == (variant == ProjectVariant.APPLICATION))
        assertTrue("main.kts must exist only for the script variant", Files.isRegularFile(root.resolve("src/main/kotlin/main.kts")) == (variant == ProjectVariant.SCRIPT))

        val hasGradleWrapper = buildSystem != BuildSystemType.Maven
        assertTrue(
            "gradle wrapper must be generated for Gradle projects",
            Files.isRegularFile(root.resolve("gradlew")) == hasGradleWrapper
        )
        if (hasGradleWrapper) {
            val properties = Files.readString(root.resolve("gradle/wrapper/gradle-wrapper.properties"))
            assertTrue(
                "wrapper must use Gradle $GENERATED_GRADLE_VERSION, was: $properties",
                properties.contains("gradle-$GENERATED_GRADLE_VERSION-bin.zip")
            )
        }
    }

    /** Maven projects get the coordinates entered in the wizard. */
    fun testMavenProjectContainsCoordinates() {
        val request = request(ProjectVariant.LIBRARY, BuildSystemType.Maven)
        generator.generate(request)

        val pom = Files.readString(request.directory.resolve("pom.xml"))
        assertTrue(pom.contains("<groupId>org.example</groupId>"))
        assertTrue(pom.contains("<artifactId>demo</artifactId>"))
        assertTrue(pom.contains("<version>1.0-SNAPSHOT</version>"))
    }

    /** The application template's internal `consoleApp` module must not become the Maven name. */
    fun testApplicationMavenProjectUsesRequestedName() {
        val request = request(ProjectVariant.APPLICATION, BuildSystemType.Maven)
        generator.generate(request)

        val pom = Files.readString(request.directory.resolve("pom.xml"))
        assertTrue("the artifact ID must come from the wizard, was: $pom", pom.contains("<artifactId>demo</artifactId>"))
        assertFalse("the internal module name must not leak into the project, was: $pom", pom.contains("consoleApp"))
    }

    /** A script project's `main.kts` is a valid Kotlin script. */
    fun testScriptProjectContainsScript() {
        val request = request(ProjectVariant.SCRIPT, BuildSystemType.GradleKotlinDsl)
        generator.generate(request)

        assertEquals(
            "println(\"Hello, Kotlin!\")\n",
            Files.readString(request.directory.resolve("src/main/kotlin/main.kts"))
        )
    }

    /** An invalid request is rejected before anything is written. */
    fun testInvalidRequestThrows() {
        val request = request(ProjectVariant.APPLICATION, BuildSystemType.Maven).copy(name = "bad/name")
        try {
            generator.generate(request)
            fail("invalid request must not be generated")
        } catch (expected: KotlinProjectGenerationException) {
            assertTrue(expected.message!!.contains(KotlinProjectProblem.INVALID_NAME.name))
        }
        assertFalse("nothing must be written for an invalid request", Files.exists(request.directory))
    }

    /** Cancellation is reported through the dedicated exception and stops generation. */
    fun testCancellationThrows() {
        val request = request(ProjectVariant.APPLICATION, BuildSystemType.Maven)
        try {
            generator.generate(request, isCancelled = { true })
            fail("cancelled generation must not complete")
        } catch (expected: KotlinProjectGenerationCancelledException) {
            assertNotNull(expected.message)
        }
    }

    /** Generation into an existing empty directory is allowed. */
    fun testGeneratesIntoExistingEmptyDirectory() {
        val request = request(ProjectVariant.LIBRARY, BuildSystemType.Maven)
        Files.createDirectories(request.directory)

        generator.generate(request)

        assertTrue(Files.isRegularFile(request.directory.resolve("pom.xml")))
    }

    /** The returned path must be inside `getWorkDir()` for every combination. */
    fun testGeneratedDirectoriesAreUnderWorkDir() {
        val workDir = getWorkDir().toPath()
        for (variant in ProjectVariant.values()) {
            for (buildSystem in buildSystems) {
                val root: Path = generator.generate(request(variant, buildSystem))
                assertTrue("$root must be under $workDir", root.startsWith(workDir))
            }
        }
    }
}
