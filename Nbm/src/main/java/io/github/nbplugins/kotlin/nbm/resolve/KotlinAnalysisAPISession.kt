/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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

import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.projectsextensions.KotlinProjectHelper
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.java.classpath.ClassPath
import org.netbeans.api.project.Project as NBProject
import java.nio.file.Path

/**
 * Manages a K2 Analysis API session ([StandaloneAnalysisAPISession]) for a single NetBeans project.
 *
 * One instance is created per open [NBProject] and cached for the project's lifetime
 * (see [getSession]). The IntelliJ application environment is initialised at plugin startup
 * by [io.github.nbplugins.kotlin.nbm.startup.FakeIntellijHome.startUp], which satisfies the
 * `PathManager.getHomePath()` requirement before `buildStandaloneAnalysisAPISession` is called.
 *
 * This class belongs to the **service/model** layer and must not reference NetBeans UI APIs.
 *
 * @param nbProject the NetBeans project this session analyses
 */
class KotlinAnalysisAPISession private constructor(nbProject: NBProject) {

    /**
     * The underlying K2 standalone analysis session.
     *
     * Use [org.jetbrains.kotlin.analysis.api.analyze] blocks to run analysis:
     * ```kotlin
     * val session = KotlinAnalysisAPISession.getSession(project)
     * analyze(ktFile) { ... }
     * ```
     */
    val session: StandaloneAnalysisAPISession

    init {
        val startTime = System.nanoTime()

        val binaryJars: List<Path> = collectBinaryJars(nbProject)
        val sourceRoots: List<Path> = collectSourceRoots(nbProject)

        session = buildStandaloneAnalysisAPISession {
            buildKtModuleProvider {
                platform = JvmPlatforms.unspecifiedJvmPlatform

                val libModules = binaryJars.map { jar ->
                    addModule(buildKtLibraryModule {
                        libraryName = jar.fileName.toString()
                        addBinaryRoot(jar)
                        platform = JvmPlatforms.unspecifiedJvmPlatform
                    })
                }

                addModule(buildKtSourceModule {
                    moduleName = nbProject.projectDirectory.name
                    languageVersionSettings = LanguageVersionSettingsImpl(
                        LanguageVersion.KOTLIN_2_0, ApiVersion.KOTLIN_2_0
                    )
                    sourceRoots.forEach { addSourceRoot(it) }
                    libModules.forEach { addRegularDependency(it) }
                    platform = JvmPlatforms.unspecifiedJvmPlatform
                })
            }
        }

        KotlinLogger.INSTANCE.logInfo(
            "KotlinAnalysisAPISession init for '${nbProject.projectDirectory.name}': " +
            "${(System.nanoTime() - startTime)} ns, " +
            "${binaryJars.size} binary jars, ${sourceRoots.size} source roots"
        )
    }

    companion object {
        private val cache = hashMapOf<NBProject, KotlinAnalysisAPISession>()

        @Volatile private var appEnvInitialized = false

        /**
         * A session retained solely to keep the K2 application environment alive.
         *
         * Discarding it would allow its Disposable to be finalized, which could tear down
         * services registered on the shared application. Kept as a strong reference.
         */
        @Volatile private var initSession: StandaloneAnalysisAPISession? = null

        /**
         * Initialises the K2 standalone application environment exactly once, without
         * binding it to a specific NetBeans project.
         *
         * Must be called before [KotlinEnvironment.getEnvironment] is first invoked, so that
         * [KotlinCoreEnvironment.getOrCreateApplicationEnvironmentForProduction] reuses the
         * already-created application environment and skips `PluginDescriptorLoader.loadForCoreEnv`
         * (which causes [ClassNotFoundException] for inner classes in plugin descriptor XML).
         *
         * Safe to call multiple times; subsequent calls are no-ops.
         */
        @Synchronized
        fun initApplicationEnvironment() {
            if (appEnvInitialized) return
            initSession = buildStandaloneAnalysisAPISession {
                buildKtModuleProvider {
                    platform = JvmPlatforms.unspecifiedJvmPlatform
                    addModule(buildKtSourceModule {
                        moduleName = "nbkotlin-app-env-init"
                        languageVersionSettings = LanguageVersionSettingsImpl(
                            LanguageVersion.KOTLIN_2_0, ApiVersion.KOTLIN_2_0
                        )
                        platform = JvmPlatforms.unspecifiedJvmPlatform
                    })
                }
            }
            appEnvInitialized = true
        }

        /**
         * Returns the cached [KotlinAnalysisAPISession] for [nbProject], creating and caching
         * a new instance on the first call for that project.
         *
         * @param nbProject the NetBeans project for which the session is needed
         * @return the (possibly newly created) session for [nbProject]
         */
        @Synchronized
        fun getSession(nbProject: NBProject): KotlinAnalysisAPISession =
            cache.getOrPut(nbProject) { KotlinAnalysisAPISession(nbProject) }

        /**
         * Removes all cached sessions from the cache.
         *
         * Call when the plugin is unloaded or all projects are closed to release resources.
         * The next call to [getSession] will create a fresh instance.
         */
        @Synchronized
        fun disposeAll() {
            cache.clear()
        }

        /**
         * Collects binary JAR paths from the project classpath.
         *
         * Only `.jar` files that exist on disk are included; directory roots and
         * `!/`-suffixed virtual paths used by the K1 classpath are excluded.
         *
         * @param nbProject the NetBeans project
         * @return list of existing JAR [Path]s
         */
        private fun collectBinaryJars(nbProject: NBProject): List<Path> =
            ProjectUtils.getClasspath(nbProject)
                .filter { it.endsWith(".jar") }
                .map { Path.of(it) }
                .filter { it.toFile().exists() }

        /**
         * Collects source root paths from the project's SOURCE classpath.
         *
         * @param nbProject the NetBeans project
         * @return list of source root [Path]s, or an empty list if none are available
         */
        private fun collectSourceRoots(nbProject: NBProject): List<Path> =
            with(KotlinProjectHelper) { nbProject.getExtendedClassPath() }
                ?.getProjectSourcesClassPath(ClassPath.SOURCE)
                ?.entries()
                ?.mapNotNull { entry: org.netbeans.api.java.classpath.ClassPath.Entry ->
                    try { Path.of(entry.url.toURI()) } catch (_: Exception) { null }
                }
                ?: emptyList()
    }
}
