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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A potentially invalid aspect of a [KotlinProjectRequest].
 *
 * The enum carries no user-facing text: the wizard panel maps each problem to a localized
 * message, while [KotlinProjectRequest.validate] stays UI-free and testable.
 */
enum class KotlinProjectProblem {
    /** Project name is blank. */
    EMPTY_NAME,

    /** Project name contains characters not allowed in a project name. */
    INVALID_NAME,

    /** Project location cannot be used as a directory path on this platform. */
    INVALID_LOCATION,

    /** Group ID is blank. */
    EMPTY_GROUP_ID,

    /** Group ID contains characters not allowed in a Maven group ID. */
    INVALID_GROUP_ID,

    /** Artifact ID is blank. */
    EMPTY_ARTIFACT_ID,

    /** Artifact ID contains characters not allowed in an artifact ID. */
    INVALID_ARTIFACT_ID,

    /** Version is blank. */
    EMPTY_VERSION,

    /** Version contains characters not allowed in a version. */
    INVALID_VERSION,

    /** The target directory already exists and is not empty. */
    DIRECTORY_NOT_EMPTY,

    /** The target path exists but is a file, so the project cannot be created there. */
    PATH_NOT_DIRECTORY,

    /** The project location contains a file, so the project directory cannot be created. */
    LOCATION_NOT_DIRECTORY,

    /** The target directory or its nearest existing parent cannot be written. */
    NOT_WRITABLE,

    /** The target directory exists but cannot be listed, so generation cannot proceed. */
    DIRECTORY_NOT_READABLE,
}

/**
 * Result of validating a [KotlinProjectRequest].
 *
 * @property errors issues that must block project generation.
 * @property warnings issues worth telling the user about but which do not block generation.
 */
data class KotlinProjectValidation(
    val errors: List<KotlinProjectProblem>,
    val warnings: List<KotlinProjectProblem>,
) {
    /** `true` when [errors] is empty, i.e. the project may be generated. */
    val isValid: Boolean
        get() = errors.isEmpty()

    companion object {
        /** Validation result without errors and warnings. */
        val OK = KotlinProjectValidation(emptyList(), emptyList())
    }
}

/**
 * Everything the [KotlinProjectGenerator] needs to create a Kotlin project.
 *
 * @property directory absolute path of the project root directory to create.
 * @property name project (and therefore directory) name.
 * @property groupId Maven group ID written into the build file.
 * @property artifactId Maven artifact ID written into the build file.
 * @property version project version written into the build file.
 * @property buildSystem build system to generate.
 * @property variant which kind of Kotlin project to generate.
 */
data class KotlinProjectRequest(
    val directory: Path,
    val name: String,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val buildSystem: BuildSystemType,
    val variant: ProjectVariant,
) {

    /**
     * Validates the request. The project name follows the IDEA New Project Wizard rules:
     * letters, digits, space, `-`, `_` and `.`, except the path segments `.` and `..`, which
     * would move the project directory out of the selected location. Coordinates are stricter
     * because Maven rejects anything else: the group ID and the artifact ID may contain only
     * ASCII letters, digits, `-`, `_` and `.` (and must not be `.` or `..`), while the version
     * may contain letters, digits, `-`, `_` and `.`.
     *
     * An existing non-empty directory is only a warning: IDEA itself allows generating into
     * a non-empty folder.
     */
    fun validate(): KotlinProjectValidation {
        val directoryProblem = directoryProblem()
        val errors = buildList {
            when {
                name.isBlank() -> add(KotlinProjectProblem.EMPTY_NAME)
                !isModuleIdentifier(name) || name == "." || name == ".." -> add(KotlinProjectProblem.INVALID_NAME)
            }
            when {
                groupId.isBlank() -> add(KotlinProjectProblem.EMPTY_GROUP_ID)
                !isGroupId(groupId) -> add(KotlinProjectProblem.INVALID_GROUP_ID)
            }
            when {
                artifactId.isBlank() -> add(KotlinProjectProblem.EMPTY_ARTIFACT_ID)
                !isArtifactId(artifactId) -> add(KotlinProjectProblem.INVALID_ARTIFACT_ID)
            }
            when {
                version.isBlank() -> add(KotlinProjectProblem.EMPTY_VERSION)
                !isCoordinateIdentifier(version) -> add(KotlinProjectProblem.INVALID_VERSION)
            }
            when (directoryProblem) {
                KotlinProjectProblem.DIRECTORY_NOT_READABLE -> add(KotlinProjectProblem.DIRECTORY_NOT_READABLE)
                KotlinProjectProblem.PATH_NOT_DIRECTORY -> add(KotlinProjectProblem.PATH_NOT_DIRECTORY)
                KotlinProjectProblem.LOCATION_NOT_DIRECTORY -> add(KotlinProjectProblem.LOCATION_NOT_DIRECTORY)
                KotlinProjectProblem.NOT_WRITABLE -> add(KotlinProjectProblem.NOT_WRITABLE)
                else -> Unit
            }
        }
        val warnings = if (directoryProblem == KotlinProjectProblem.DIRECTORY_NOT_EMPTY) {
            listOf(KotlinProjectProblem.DIRECTORY_NOT_EMPTY)
        } else {
            emptyList()
        }
        return KotlinProjectValidation(errors, warnings)
    }

    /**
     * Inspects the target directory without throwing: validation runs on every keystroke from
     * swing document listeners, where an exception would leave the panel state stale.
     *
     * @return [KotlinProjectProblem.DIRECTORY_NOT_EMPTY] when the directory has content,
     * [KotlinProjectProblem.DIRECTORY_NOT_READABLE] when it cannot be listed,
     * [KotlinProjectProblem.PATH_NOT_DIRECTORY] when the path exists but is a file,
     * [KotlinProjectProblem.LOCATION_NOT_DIRECTORY] when a parent of the path is a file,
     * [KotlinProjectProblem.NOT_WRITABLE] when neither the directory nor the nearest existing
     * parent can be written, `null` when the project can be generated there
     */
    private fun directoryProblem(): KotlinProjectProblem? = when {
        Files.isDirectory(directory) -> existingDirectoryProblem()
        Files.exists(directory) -> KotlinProjectProblem.PATH_NOT_DIRECTORY
        else -> parentProblem()
    }

    /**
     * Inspects the target directory that already exists.
     *
     * Listing comes first: a directory that cannot be read reports that problem, otherwise an
     * unreadable-and-unwritable directory would be reported as merely unwritable.
     *
     * @return the problem of the existing directory, or `null` when it can be used
     */
    private fun existingDirectoryProblem(): KotlinProjectProblem? = try {
        val isNotEmpty = Files.list(directory).use { it.findAny().isPresent }
        when {
            !Files.isWritable(directory) -> KotlinProjectProblem.NOT_WRITABLE
            isNotEmpty -> KotlinProjectProblem.DIRECTORY_NOT_EMPTY
            else -> null
        }
    } catch (_: IOException) {
        KotlinProjectProblem.DIRECTORY_NOT_READABLE
    }

    /**
     * Inspects the nearest existing ancestor of a target that does not exist yet.
     *
     * A nonexistent target below a file (for instance, location `/tmp/location.txt` yields
     * `/tmp/location.txt/demo`) cannot be created, and a missing intermediate directory
     * elsewhere in the path is legal, so only the nearest existing ancestor decides.
     *
     * @return the problem of the nearest existing ancestor, or `null` when the missing path is
     * below a writable directory
     */
    private fun parentProblem(): KotlinProjectProblem? {
        var ancestor = directory.parent
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.parent
        }
        return when {
            ancestor == null -> null
            !Files.isDirectory(ancestor) -> KotlinProjectProblem.LOCATION_NOT_DIRECTORY
            !Files.isWritable(ancestor) -> KotlinProjectProblem.NOT_WRITABLE
            else -> null
        }
    }

    companion object {
        /** Special characters IDEA allows in module/project and artifact names. */
        val MODULE_NAME_SPECIAL_CHARS: Set<Char> = setOf('-', '_', '.', ' ')

        /** Special characters IDEA allows in a group ID and a version. */
        val COORDINATE_SPECIAL_CHARS: Set<Char> = setOf('-', '_', '.')

        /**
         * @return `true` when [value] is non-blank and may serve as a project or artifact name.
         */
        @JvmStatic
        fun isModuleIdentifier(value: String): Boolean =
            hasOnlyAllowedChars(value, MODULE_NAME_SPECIAL_CHARS)

        /**
         * @return `true` when [value] is a legal Maven identifier, i.e. matches
         * `[A-Za-z0-9_.-]+`; Maven rejects spaces and non-ASCII letters. The path segments `.`
         * and `..` are also rejected: they would turn into directory navigation in the
         * generated project path.
         */
        @JvmStatic
        fun isMavenIdentifier(value: String): Boolean =
            value.isNotEmpty() && value != "." && value != ".." &&
                value.all { it.isAsciiLetterOrDigit() || it in COORDINATE_SPECIAL_CHARS }

        /**
         * @return `true` when [value] is non-blank and a legal Maven group ID.
         */
        @JvmStatic
        fun isGroupId(value: String): Boolean = isMavenIdentifier(value)

        /**
         * Checks the Maven artifact ID pattern `[A-Za-z0-9_.-]+`: Maven rejects spaces and
         * non-ASCII letters, so such an ID cannot produce a working project.
         *
         * @return `true` when [value] is non-blank and a legal Maven artifact ID.
         */
        @JvmStatic
        fun isArtifactId(value: String): Boolean = isMavenIdentifier(value)

        /**
         * @return `true` when [value] is non-blank and may serve as a group ID or a version.
         */
        @JvmStatic
        fun isCoordinateIdentifier(value: String): Boolean =
            hasOnlyAllowedChars(value, COORDINATE_SPECIAL_CHARS)

        private fun hasOnlyAllowedChars(value: String, allowedExtraChars: Set<Char>): Boolean =
            value.isNotBlank() && value.all { it.isLetterOrDigit() || it in allowedExtraChars }

        /**
         * @return `true` for `A`–`Z`, `a`–`z` and `0`–`9` only, unlike [Char.isLetterOrDigit],
         * which also accepts letters of other scripts.
         */
        private fun Char.isAsciiLetterOrDigit(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
