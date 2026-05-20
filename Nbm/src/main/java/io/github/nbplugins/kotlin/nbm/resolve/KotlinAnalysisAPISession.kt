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

import com.intellij.codeInsight.multiverse.CodeInsightContextManager
import com.intellij.codeInsight.multiverse.CodeInsightContextManagerStub
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
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
class KotlinAnalysisAPISession private constructor(
    moduleName: String,
    binaryJars: List<Path>,
    sourceRoots: List<Path>
) {
    /**
     * Secondary constructor that derives [moduleName], [binaryJars], and [sourceRoots]
     * from a NetBeans project.  This is the normal production path.
     *
     * @param nbProject the NetBeans project this session analyses
     */
    private constructor(nbProject: NBProject) : this(
        moduleName = nbProject.projectDirectory.name,
        binaryJars = collectBinaryJars(nbProject),
        sourceRoots = collectSourceRoots(nbProject)
    )

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

    /**
     * `true` when the session was initialised with at least one binary JAR on the classpath.
     *
     * K2 diagnostics are only reliable when binary dependencies are available; without them,
     * every external reference appears unresolved and produces false-positive errors.
     * [KotlinParserResult.getDiagnostics] checks this flag before using K2 as the primary
     * diagnostics source.
     */
    val hasDependencies: Boolean

    init {
        val startTime = System.nanoTime()

        hasDependencies = binaryJars.isNotEmpty()

        session = buildStandaloneAnalysisAPISession {
            registerProjectService(CodeInsightContextManager::class.java, CodeInsightContextManagerStub())
            buildKtModuleProvider {
                platform = JvmPlatforms.unspecifiedJvmPlatform

                val jdkModule = addModule(buildKtSdkModule {
                    libraryName = "JDK"
                    addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), false)
                    platform = JvmPlatforms.unspecifiedJvmPlatform
                })

                val libModules = binaryJars.map { jar ->
                    addModule(buildKtLibraryModule {
                        libraryName = jar.fileName.toString()
                        addBinaryRoot(jar)
                        platform = JvmPlatforms.unspecifiedJvmPlatform
                    })
                }

                addModule(buildKtSourceModule {
                    this.moduleName = moduleName
                    languageVersionSettings = LanguageVersionSettingsImpl(
                        LanguageVersion.KOTLIN_2_0, ApiVersion.KOTLIN_2_0
                    )
                    sourceRoots.forEach { addSourceRoot(it) }
                    addRegularDependency(jdkModule)
                    libModules.forEach { addRegularDependency(it) }
                    platform = JvmPlatforms.unspecifiedJvmPlatform
                })
            }
        }

        KotlinLogger.INSTANCE.logInfo(
            "KotlinAnalysisAPISession init for '$moduleName': " +
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
                registerProjectService(CodeInsightContextManager::class.java, CodeInsightContextManagerStub())
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
         * Removes the cached session for [nbProject] so the next [getSession] call creates a
         * fresh session from the current on-disk sources.
         *
         * Call after an in-editor modification (e.g. after a hint's [KaApplicableIntention.implement]
         * has edited the document and the file has been saved), so that the subsequent parse picks
         * up the updated K2 PSI rather than the stale pre-edit tree.
         *
         * @param nbProject the project whose cached session should be invalidated
         */
        @Synchronized
        fun invalidate(nbProject: NBProject) {
            cache.remove(nbProject)
        }

        /**
         * Creates a [KotlinAnalysisAPISession] with an explicit list of binary JARs and
         * source roots, bypassing project-classpath resolution.
         *
         * Intended for **tests only**: use this when the NetBeans test project has no
         * binary dependencies configured (e.g. `projForTest` has no Kotlin stdlib), but the
         * test still needs a fully functional K2 session with stdlib on the classpath.
         *
         * The returned session is NOT cached in [cache]; the caller owns its lifetime.
         *
         * @param moduleName  name to assign to the K2 source module
         * @param binaryJars  binary JAR dependencies (e.g. kotlin-stdlib)
         * @param sourceRoots source roots to include in the module
         * @return a new [KotlinAnalysisAPISession] configured with the supplied JARs
         */
        fun createWithJars(
            moduleName: String,
            binaryJars: List<Path>,
            sourceRoots: List<Path>
        ): KotlinAnalysisAPISession = KotlinAnalysisAPISession(moduleName, binaryJars, sourceRoots)

        /**
         * Collects binary JAR paths from the project classpath.
         *
         * Normalises each entry: strips a leading `file:` scheme and a trailing `!/`
         * (the NetBeans Maven integration returns `jar:file:/path/to.jar!/`-style strings
         * whose `.getPath()` still has the `file:` prefix and `!/` suffix).
         * Only entries whose normalised path ends in `.jar` and exist on disk are included.
         *
         * @param nbProject the NetBeans project
         * @return list of existing JAR [Path]s
         */
        private fun collectBinaryJars(nbProject: NBProject): List<Path> =
            ProjectUtils.getClasspath(nbProject)
                .map { raw ->
                    var s = raw
                    if (s.startsWith("file:")) s = s.removePrefix("file:")
                    if (s.endsWith("!/")) s = s.removeSuffix("!/")
                    s
                }
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

    /**
     * Returns the K2 [KtFile] owned by this session whose virtual file path equals [path],
     * or `null` if no source file with that path is registered in this session's source module.
     *
     * Use the returned [KtFile] — never a K1 [KtFile] from [KotlinEnvironment] — with
     * [org.jetbrains.kotlin.analysis.api.analyze] blocks.
     *
     * @param path absolute path of the source file
     * @return the K2-session-owned [KtFile], or `null` if not found
     */
    fun getKtFileForPath(path: String): KtFile? =
        session.modulesWithFiles.values
            .flatten()
            .filterIsInstance<KtFile>()
            .firstOrNull { it.virtualFile?.path == path }
}
