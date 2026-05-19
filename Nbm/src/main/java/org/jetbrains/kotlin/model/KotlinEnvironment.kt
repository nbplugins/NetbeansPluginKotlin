/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.model

import com.intellij.openapi.project.Project
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.filesystem.KotlinLightClassManager
import org.netbeans.api.project.Project as NBProject

/**
 * Thin wrapper around the K2 [KotlinAnalysisAPISession] that provides access to the
 * IntelliJ [Project] for plugin features such as the formatter and light-class manager.
 *
 * All K1 environment setup (JavaRoot, JvmDependenciesIndex, CliTraceHolder, etc.) has been
 * removed — the K2 standalone session manages classpath scanning internally.
 *
 * @param nbProject the NetBeans project this environment represents
 */
class KotlinEnvironment private constructor(val nbProject: NBProject) {

    /**
     * The IntelliJ [Project] backing this environment, sourced from the K2 analysis session.
     *
     * Callers (formatter, light-class manager) use this to look up project services.
     */
    val project: Project
        get() = KotlinAnalysisAPISession.getSession(nbProject).session.project

    /**
     * Light-class manager for this project.  Stored here instead of as an IntelliJ project
     * service because [Project.registerService] is not exposed by the 253-era interface.
     */
    val lightClassManager: KotlinLightClassManager = KotlinLightClassManager(nbProject)

    companion object {

        private val cache = hashMapOf<NBProject, KotlinEnvironment>()

        /**
         * Returns the cached [KotlinEnvironment] for [kotlinProject], creating and caching it
         * on the first call.
         *
         * @param kotlinProject the NetBeans project
         * @return the environment for that project
         */
        @Synchronized
        fun getEnvironment(kotlinProject: NBProject): KotlinEnvironment =
            cache.getOrPut(kotlinProject) { KotlinEnvironment(kotlinProject) }

        /**
         * Clears the cached environment for [kotlinProject] so the next call to
         * [getEnvironment] creates a fresh instance.
         *
         * @param kotlinProject the project whose environment should be invalidated
         */
        @Synchronized
        fun updateKotlinEnvironment(kotlinProject: NBProject) {
            cache.remove(kotlinProject)
        }
    }
}
