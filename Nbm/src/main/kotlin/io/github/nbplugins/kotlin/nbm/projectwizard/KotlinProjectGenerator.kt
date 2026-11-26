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

import org.jetbrains.kotlin.tools.projectWizard.core.Failure
import org.jetbrains.kotlin.tools.projectWizard.core.TaskResult
import org.jetbrains.kotlin.tools.projectWizard.core.computeM
import org.jetbrains.kotlin.tools.projectWizard.core.entity.PipelineTask
import org.jetbrains.kotlin.tools.projectWizard.core.entity.settings.reference
import org.jetbrains.kotlin.tools.projectWizard.core.service.IdeaIndependentWizardService
import org.jetbrains.kotlin.tools.projectWizard.core.service.Services
import org.jetbrains.kotlin.tools.projectWizard.core.service.ServicesManager
import org.jetbrains.kotlin.tools.projectWizard.core.service.WizardService
import org.jetbrains.kotlin.tools.projectWizard.phases.GenerationPhase
import org.jetbrains.kotlin.tools.projectWizard.plugins.Plugins
import org.jetbrains.kotlin.tools.projectWizard.plugins.StructurePlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.buildSystem.BuildSystemPlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.buildSystem.BuildSystemType
import org.jetbrains.kotlin.tools.projectWizard.plugins.buildSystem.gradle.GradlePlugin
import org.jetbrains.kotlin.tools.projectWizard.plugins.projectTemplates.applyProjectTemplate
import org.jetbrains.kotlin.tools.projectWizard.settings.version.Version
import org.jetbrains.kotlin.tools.projectWizard.wizard.Wizard
import java.nio.file.Files
import java.nio.file.Path

/**
 * Thrown when the IDEA New Project Wizard reports a failure while generating a project.
 *
 * @property details individual error messages reported by the wizard.
 */
class KotlinProjectGenerationException(
    message: String,
    val details: List<String> = emptyList(),
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when project generation is cancelled by the caller through the cancellation check
 * passed to [KotlinProjectGenerator.generate].
 */
class KotlinProjectGenerationCancelledException : Exception("Kotlin project generation was cancelled")

/**
 * Generates a Kotlin project on disk using the IDEA New Project Wizard core.
 *
 * This is the service layer of the New Project wizard: it knows nothing about NetBeans UI and
 * only needs an application environment where [ProjectWizardEnvironment] has registered the
 * wizard's services.
 */
class KotlinProjectGenerator {

    /**
     * Generates the project described by [request] and returns its root directory.
     *
     * @param request validated description of the project to create.
     * @param onProgress called with the title of every wizard task before it runs; suitable for
     *        updating a progress bar.
     * @param isCancelled polled before every task; when it returns `true` generation stops with
     *        [KotlinProjectGenerationCancelledException].
     * @return the project root directory ([KotlinProjectRequest.directory]).
     * @throws KotlinProjectGenerationException when the request is invalid or the wizard fails.
     * @throws KotlinProjectGenerationCancelledException when [isCancelled] returns `true`.
     */
    fun generate(
        request: KotlinProjectRequest,
        onProgress: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Path {
        ProjectWizardEnvironment.ensureRegistered()

        val validation = request.validate()
        if (!validation.isValid) {
            throw KotlinProjectGenerationException(
                "Cannot generate project ${request.name}: ${validation.errors.joinToString()}"
            )
        }

        val result = NetBeansKotlinProjectWizard(request, onProgress, isCancelled).run()
        if (result is Failure) {
            throw KotlinProjectGenerationException(
                "Cannot generate project ${request.name}",
                result.errors.map { it.message }
            )
        }

        writeScriptIfNeeded(request)
        checkGeneratedBuildFile(request)
        return request.directory
    }

    /**
     * Writes `main.kts` for [ProjectVariant.SCRIPT]; the wizard itself generates no scripts.
     */
    private fun writeScriptIfNeeded(request: KotlinProjectRequest) {
        val relativePath = request.variant.scriptRelativePath ?: return
        val script = request.directory.resolve(relativePath)
        Files.createDirectories(script.parent)
        Files.writeString(script, "println(\"Hello, Kotlin!\")\n")
    }

    /**
     * Fails loudly when the generated build file is missing or does not reference
     * [GENERATED_KOTLIN_VERSION]. Such a file means the wizard silently fell back to the
     * IntelliJ default Kotlin version, which must not reach users.
     */
    private fun checkGeneratedBuildFile(request: KotlinProjectRequest) {
        val buildFile = request.directory.resolve(request.buildSystem.buildFileName)
        if (!Files.isRegularFile(buildFile)) {
            throw KotlinProjectGenerationException("The wizard did not create $buildFile")
        }
        val text = Files.readString(buildFile)
        if (!text.contains(GENERATED_KOTLIN_VERSION)) {
            throw KotlinProjectGenerationException(
                "$buildFile does not reference Kotlin $GENERATED_KOTLIN_VERSION"
            )
        }
    }

    /**
     * Name of the build file generated for every [BuildSystemType] the wizard supports.
     */
    private val BuildSystemType.buildFileName: String
        get() = when (this) {
            BuildSystemType.GradleKotlinDsl -> "build.gradle.kts"
            BuildSystemType.GradleGroovyDsl -> "build.gradle"
            BuildSystemType.Maven -> "pom.xml"
            // NetBeans never offers JPS, but the wizard enum still contains it.
            BuildSystemType.Jps -> error("JPS projects are not supported")
        }
}

/**
 * A [Wizard] that applies our [KotlinProjectRequest] instead of the IDEA UI state.
 *
 * Mirrors the upstream `ProjectTemplateBasedTestWizard`: run [GenerationPhase.PREPARE] once,
 * write the project settings, then run every generation phase.
 */
private class NetBeansKotlinProjectWizard(
    private val request: KotlinProjectRequest,
    private val onProgress: (String) -> Unit,
    private val isCancelled: () -> Boolean,
) : Wizard(Plugins.allPlugins, SERVICES_MANAGER, isUnitTestMode = true) {

    /**
     * Executes the whole wizard pipeline and reports tasks through [onProgress].
     */
    fun run(): TaskResult<Unit> = apply(Services.IDEA_INDEPENDENT_SERVICES, GenerationPhase.ALL) { task ->
        if (isCancelled()) throw KotlinProjectGenerationCancelledException()
        task.title?.let(onProgress)
    }

    override fun apply(
        services: List<WizardService>,
        phases: Set<GenerationPhase>,
        onTaskExecuting: (PipelineTask) -> Unit,
    ): TaskResult<Unit> = computeM {
        // The first pass initialises defaults and lets the template set its own values.
        super.apply(services, setOf(GenerationPhase.PREPARE), onTaskExecuting).ensure()
        context.writeSettings {
            applyProjectTemplate(request.variant.template())
            BuildSystemPlugin.type.reference.setValue(request.buildSystem)
            StructurePlugin.projectPath.reference.setValue(request.directory)
            StructurePlugin.name.reference.setValue(request.name)
            StructurePlugin.groupId.reference.setValue(request.groupId)
            StructurePlugin.artifactId.reference.setValue(request.artifactId)
            StructurePlugin.version.reference.setValue(request.version)
            GradlePlugin.gradleVersion.reference.setValue(Version.fromString(GRADLE_WRAPPER_VERSION))
        }
        super.apply(services, phases, onTaskExecuting)
    }

    private companion object {
        val SERVICES_MANAGER = ServicesManager(Services.IDEA_INDEPENDENT_SERVICES) { services ->
            services.firstOrNull { it is IdeaIndependentWizardService }
        }
    }
}

/**
 * Gradle version used for the generated wrapper.
 *
 * Newer than the wizard's default 8.1.1 because NetBeans runs on JDK 21+, which Gradle 8.1.1
 * does not support.
 */
private const val GRADLE_WRAPPER_VERSION = "8.14.3"
