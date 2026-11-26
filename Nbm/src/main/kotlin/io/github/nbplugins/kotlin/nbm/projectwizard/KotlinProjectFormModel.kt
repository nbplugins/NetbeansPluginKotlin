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
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * UI-free state of the New Project → Kotlin wizard panel.
 *
 * Holds exactly the values the user edits. Everything that can be derived — the project
 * directory, the [KotlinProjectRequest] and its [validation][KotlinProjectValidation] — is
 * computed on demand, so the swing panel stays a pure view and this class is unit-testable
 * without NetBeans UI.
 *
 * @param variant the Kotlin project kind the wizard creates; determines the default project
 * name and the variant of the produced request.
 */
class KotlinProjectFormModel(val variant: ProjectVariant) {

    /** User-entered project name, also the name of the generated project directory. */
    var projectName: String = variant.defaultProjectName()
        set(value) {
            if (field == value) return
            // Keep the artifact ID in sync while the user has not customized it: the artifact
            // ID cannot contain spaces (a project name can), so mirroring the sanitized name is
            // what IDEA does too.
            val artifactIdWasDerived = artifactId == artifactIdFor(field)
            field = value
            if (artifactIdWasDerived) {
                artifactId = artifactIdFor(value)
            }
        }

    /** Directory that contains the project directory. */
    var location: String = defaultProjectsLocation()

    /** Maven group ID written into the generated build file. */
    var groupId: String = DEFAULT_GROUP_ID

    /** Maven artifact ID written into the generated build file. */
    var artifactId: String = artifactIdFor(variant.defaultProjectName())

    /** Project version written into the generated build file. */
    var version: String = DEFAULT_VERSION

    /** Build system of the generated project. */
    var buildSystem: BuildSystemType = DEFAULT_BUILD_SYSTEM

    /**
     * Absolute path of the project directory that will be generated.
     *
     * Normalized, because NetBeans' `FileUtil.toFileObject` rejects paths that contain `.` or
     * `..` components.
     */
    val projectDirectory: Path
        get() = Paths.get(location.ifBlank { defaultProjectsLocation() }).resolve(projectName).normalize()

    /**
     * @return the current request, including the [validation][KotlinProjectRequest.validate]
     * results for the current field values.
     */
    fun toRequest(): KotlinProjectRequest = KotlinProjectRequest(
        directory = projectDirectory,
        name = projectName,
        groupId = groupId,
        artifactId = artifactId,
        version = version,
        buildSystem = buildSystem,
        variant = variant,
    )

    /**
     * @return the validation of the current field values: which errors block generation and
     * which warnings merely inform the user.
     *
     * Names and locations that cannot appear in a file path on this platform (for instance
     * `bad|name` on Windows) are reported as errors instead of letting [InvalidPathException]
     * escape from the swing document listeners that call this method on every keystroke.
     */
    fun validation(): KotlinProjectValidation = try {
        toRequest().validate()
    } catch (_: InvalidPathException) {
        KotlinProjectValidation(pathProblems(), emptyList())
    }

    /**
     * @return `true` when the current field values allow generation.
     */
    fun isValid(): Boolean = validation().isValid

    /**
     * @return the problem to report when the project path cannot be parsed: the location is at
     * fault when it alone is unparsable, otherwise the project name is.
     */
    private fun pathProblems(): List<KotlinProjectProblem> {
        val locationIsParsable = try {
            Paths.get(location)
            true
        } catch (_: InvalidPathException) {
            false
        }
        return listOf(
            if (locationIsParsable) KotlinProjectProblem.INVALID_NAME else KotlinProjectProblem.INVALID_LOCATION
        )
    }

    companion object {
        /** Group ID of a freshly opened wizard panel. */
        const val DEFAULT_GROUP_ID: String = "org.example"

        /** Version of a freshly opened wizard panel. */
        const val DEFAULT_VERSION: String = "1.0-SNAPSHOT"

        /** Build system selected when the panel opens. */
        val DEFAULT_BUILD_SYSTEM: BuildSystemType = BuildSystemType.GradleKotlinDsl

        /**
         * @return the project name the panel starts with for [variant]: a human-readable name
         * that is also a legal project directory name.
         */
        fun ProjectVariant.defaultProjectName(): String = when (this) {
            ProjectVariant.APPLICATION -> "KotlinApplication"
            ProjectVariant.LIBRARY -> "KotlinLibrary"
            ProjectVariant.SCRIPT -> "KotlinScript"
        }

        /**
         * @return [projectName] restricted to the characters Maven allows in an artifact ID
         * (ASCII letters, digits, `-`, `_` and `.`), so a derived ID always passes validation.
         * A name without any such character yields an empty ID, which the validation reports.
         */
        fun artifactIdFor(projectName: String): String =
            projectName.filter { it.isAsciiLetterOrDigit() || it in KotlinProjectRequest.COORDINATE_SPECIAL_CHARS }

        /**
         * @return the directory new projects are created in: the user's home directory, which
         * is always writable and is where NetBeans' own project wizards start.
         */
        fun defaultProjectsLocation(): String = System.getProperty("user.home", "")

        /**
         * @return `true` for `A`–`Z`, `a`–`z` and `0`–`9` only, unlike [Char.isLetterOrDigit],
         * which also accepts letters of other scripts.
         */
        private fun Char.isAsciiLetterOrDigit(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
