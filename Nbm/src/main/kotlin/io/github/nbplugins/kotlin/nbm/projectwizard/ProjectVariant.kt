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

import org.jetbrains.kotlin.tools.projectWizard.projectTemplates.ProjectTemplate

/**
 * The Kotlin project kinds offered by NetBeans' New Project → Kotlin category.
 */
enum class ProjectVariant {

    /** Kotlin/JVM console application with a runnable `Main.kt`. */
    APPLICATION,

    /** Kotlin/JVM library: build file and `src/main/kotlin`, no sample code. */
    LIBRARY,

    /** Kotlin script project: build file, `src/main/kotlin` and a `main.kts` script. */
    SCRIPT;

    /**
     * The IDEA project template whose settings this variant applies.
     *
     * Both templates are local (see [KotlinProjectTemplate]) because the upstream ones name
     * their module `consoleApp`, which becomes the Maven `<name>` and therefore the project
     * name NetBeans displays.
     *
     * @param moduleName name of the generated module, normally the artifact ID
     * @return the template that configures the single project module
     */
    fun template(moduleName: String): ProjectTemplate = when (this) {
        APPLICATION -> KotlinApplicationProjectTemplate(moduleName)
        LIBRARY, SCRIPT -> KotlinLibraryProjectTemplate(moduleName)
    }

    /**
     * Path of the script generated for [SCRIPT] variants, relative to the project directory,
     * or `null` for variants that contain no script.
     */
    val scriptRelativePath: String?
        get() = if (this == SCRIPT) "src/main/kotlin/main.kts" else null
}
