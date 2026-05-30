/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.resolve


import com.intellij.ide.highlighter.JavaFileType
import io.github.nbplugins.kotlin.nbm.resolve.providers.LiveKotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import com.intellij.mock.MockApplication
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.api.java.classpath.ClassPath
import org.netbeans.api.project.Project as NBProject
import java.nio.file.Files
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
/**
 * In-memory [LightVirtualFile] that overrides [getPath] to return the real file-system path.
 *
 * [LightVirtualFile] has no `setPath()`, so a subclass is the only way to make
 * [KotlinAnalysisAPISession.getKtFileForPath] find the file by its real path after
 * the session registers it via `addSourceVirtualFile`.
 */
private class SourceLightVirtualFile(
    name: String,
    fileType: FileType,
    content: CharSequence,
    private val realPath: String,
) : LightVirtualFile(name, fileType, content) {
    override fun getPath(): String = realPath
}

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
     * Maps absolute source-file path → its [SourceLightVirtualFile] so that
     * [updateFileContent] can update the in-memory buffer that the K2 session reads.
     *
     * Populated at construction time by scanning all source roots; never modified afterwards
     * (a new session with fresh LVFs is created on [invalidate]).
     */
    val fileMap: Map<String, LightVirtualFile>

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

        // Scan source roots and create in-memory LVFs so that updateFileContent() can keep
        // the session's KtFile PSI in sync with live editor snapshots without rebuilding.
        val tScan = System.nanoTime()
        val lvfs = scanSourceFiles(sourceRoots)
        fileMap = lvfs.associateBy { it.path }
        KotlinLogger.INSTANCE.logInfo(
            "[PERF] KotlinAnalysisAPISession '$moduleName': scanSourceFiles=${(System.nanoTime() - tScan) / 1_000_000}ms (${lvfs.size} files)"
        )

        val tBuild = System.nanoTime()
        session = buildStandaloneAnalysisAPISession {
            buildKtModuleProvider {
                platform = JvmPlatforms.unspecifiedJvmPlatform

                val tJdk = System.nanoTime()
                val jdkModule = addModule(buildKtSdkModule {
                    libraryName = "JDK"
                    addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), false)
                    platform = JvmPlatforms.unspecifiedJvmPlatform
                })
                KotlinLogger.INSTANCE.logInfo(
                    "[PERF] KotlinAnalysisAPISession '$moduleName': JDK module=${(System.nanoTime() - tJdk) / 1_000_000}ms"
                )

                val tLibs = System.nanoTime()
                val libModules = binaryJars.map { jar ->
                    addModule(buildKtLibraryModule {
                        libraryName = jar.fileName.toString()
                        addBinaryRoot(jar)
                        platform = JvmPlatforms.unspecifiedJvmPlatform
                    })
                }
                KotlinLogger.INSTANCE.logInfo(
                    "[PERF] KotlinAnalysisAPISession '$moduleName': library modules=${(System.nanoTime() - tLibs) / 1_000_000}ms (${binaryJars.size} jars)"
                )

                val tSrc = System.nanoTime()
                addModule(buildKtSourceModule {
                    this.moduleName = moduleName
                    languageVersionSettings = LanguageVersionSettingsImpl(
                        LanguageVersion.KOTLIN_2_0, ApiVersion.KOTLIN_2_0
                    )
                    lvfs.forEach { addSourceVirtualFile(it) }
                    addRegularDependency(jdkModule)
                    libModules.forEach { addRegularDependency(it) }
                    platform = JvmPlatforms.unspecifiedJvmPlatform
                })
                KotlinLogger.INSTANCE.logInfo(
                    "[PERF] KotlinAnalysisAPISession '$moduleName': source module=${(System.nanoTime() - tSrc) / 1_000_000}ms"
                )
            }
        }
        KotlinLogger.INSTANCE.logInfo(
            "[PERF] KotlinAnalysisAPISession '$moduleName': buildStandaloneAnalysisAPISession total=${(System.nanoTime() - tBuild) / 1_000_000}ms"
        )

        installPomModel(session)

        val tProvider = System.nanoTime()
        installLiveDeclarationProvider(session)
        KotlinLogger.INSTANCE.logInfo(
            "[PERF] KotlinAnalysisAPISession '$moduleName': installLiveDeclarationProvider=${(System.nanoTime() - tProvider) / 1_000_000}ms"
        )

        KotlinLogger.INSTANCE.logInfo(
            "[PERF] KotlinAnalysisAPISession '$moduleName': TOTAL=${(System.nanoTime() - startTime) / 1_000_000}ms " +
            "(${binaryJars.size} binary jars, ${sourceRoots.size} source roots, ${lvfs.size} source files)"
        )
    }

    /**
     * Replaces the frozen `KotlinStandaloneDeclarationProviderFactory` registered by
     * `buildStandaloneAnalysisAPISession` with a [LiveKotlinDeclarationProviderFactory]
     * over the session's source [KtFile]s.
     *
     * Required so that PSI transplanted by [updateFileContent] is visible to declaration
     * lookups; otherwise K2 throws "Classifier was found in KtFile but was not found in
     * FirFile" on the next `analyze {}` (see [LiveKotlinDeclarationProviderFactory] KDoc).
     *
     * The original factory is kept as a delegate for non-source (library/builtins) scopes.
     *
     * @param session the freshly-built standalone session whose project services are patched
     */
    /**
     * Registers a [NoOpPomModel] on the session's project. The IntelliJ
     * `ChangeUtil.prepareAndRunChangeAction` queries `PomManager.getModel(project)`
     * during every PSI mutation (e.g. `PsiElement.replace()`); without this the
     * call throws because the standalone container has no `PomModel` service.
     */
    private fun installPomModel(session: StandaloneAnalysisAPISession) {
        val project = session.project
        val mock = project as? com.intellij.mock.MockComponentManager ?: return
        if (project.getService(com.intellij.pom.PomModel::class.java) == null) {
            mock.registerService(com.intellij.pom.PomModel::class.java, NoOpPomModel())
            KotlinLogger.INSTANCE.logInfo("Registered NoOpPomModel for project ${project.name}")
        }
    }

    private fun installLiveDeclarationProvider(session: StandaloneAnalysisAPISession) {
        val project = session.project
        val mock = project as? com.intellij.mock.MockComponentManager ?: return
        val sourceKtFiles = session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>()
        val delegate = org.jetbrains.kotlin.analysis.api.platform.declarations
            .KotlinDeclarationProviderFactory.getInstance(project)
        val live = LiveKotlinDeclarationProviderFactory(sourceKtFiles, delegate)
        val key = org.jetbrains.kotlin.analysis.api.platform.declarations
            .KotlinDeclarationProviderFactory::class.java
        mock.picoContainer.unregisterComponent(key.name)
        mock.registerService(key, live)
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
            registerHighlightInfoFilterEP()
            registerStandaloneServices()
            appEnvInitialized = true
        }

        /**
         * Registers extension points required by IDEA semantic highlighting and PSI mutation
         * code in the standalone application container.
         *
         * The IDEA highlighters and PSI tree operations query several EPs at runtime. In
         * standalone mode these EPs are never registered, causing {@link IllegalArgumentException}.
         * Registering them as empty points makes the EP lookups return empty lists so all
         * highlights are accepted and no extensions are invoked.
         *
         * EPs registered:
         * - {@code com.intellij.daemon.highlightInfoFilter}: queried by {@code HighlightInfoB.isAcceptedByFilters()}
         *   and {@code AnnotationSessionImpl.create()} at highlight-creation time.
         * - {@code org.jetbrains.kotlin.callHighlighterExtension}: queried by
         *   {@code FunctionCallHighlighter.getHighlightInfoTypeForCallFromExtension()}.
         * - {@code com.intellij.treeCopyHandler}: queried by {@code ChangeUtil.encodeInformation()}
         *   when any PSI element is replaced (e.g. {@code PsiElement.replace()}). Required by
         *   modcommand-based quick-fixes that perform PSI mutations.
         * - {@code com.intellij.treeGenerator}: queried by {@code ChangeUtil.generateTreeElement()}
         *   during PSI element copy/replacement.
         */
        private fun registerHighlightInfoFilterEP() {
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            val area = app.extensionArea
            registerEpIfAbsent(area, "com.intellij.daemon.highlightInfoFilter",
                "com.intellij.codeInsight.daemon.impl.HighlightInfoFilter",
                com.intellij.openapi.extensions.ExtensionPoint.Kind.INTERFACE)
            registerEpIfAbsent(area, "org.jetbrains.kotlin.callHighlighterExtension",
                "org.jetbrains.kotlin.idea.highlighting.KotlinCallHighlighterExtension",
                com.intellij.openapi.extensions.ExtensionPoint.Kind.INTERFACE)
            registerEpIfAbsent(area, "com.intellij.treeCopyHandler",
                "com.intellij.psi.impl.source.tree.TreeCopyHandler",
                com.intellij.openapi.extensions.ExtensionPoint.Kind.INTERFACE)
            registerEpIfAbsent(area, "com.intellij.treeGenerator",
                "com.intellij.psi.impl.source.tree.TreeGenerator",
                com.intellij.openapi.extensions.ExtensionPoint.Kind.INTERFACE)
        }

        /**
         * Registers application services that are absent in the K2 standalone environment but
         * required by IDE-layer code paths invoked during PSI cache invalidation.
         *
         * [com.intellij.util.concurrency.TransferredWriteActionService] is called by
         * [com.intellij.psi.impl.PsiManagerImpl.runWriteActionOnEdtRegardlessOfCurrentThread]
         * when not on EDT. In standalone mode the real service is absent, causing an NPE.
         * The stub implementation runs the action on the calling thread, which is correct
         * for our non-EDT, non-IDE context.
         */
        private fun registerStandaloneServices() {
            val app = com.intellij.openapi.application.ApplicationManager.getApplication() as? MockApplication
            if (app == null) {
                KotlinLogger.INSTANCE.logInfo("registerStandaloneServices: app is not MockApplication (was ${com.intellij.openapi.application.ApplicationManager.getApplication()?.javaClass?.name}); skipping")
                return
            }
            if (app.getService(com.intellij.util.concurrency.TransferredWriteActionService::class.java) == null) {
                app.registerService(
                    com.intellij.util.concurrency.TransferredWriteActionService::class.java,
                    com.intellij.util.concurrency.TransferredWriteActionServiceImpl::class.java
                )
                KotlinLogger.INSTANCE.logInfo("Registered TransferredWriteActionService stub")
            }
            if (app.getService(com.intellij.psi.impl.source.codeStyle.IndentHelper::class.java) == null) {
                app.registerService(
                    com.intellij.psi.impl.source.codeStyle.IndentHelper::class.java,
                    com.intellij.psi.impl.source.codeStyle.NoOpIndentHelper::class.java
                )
                KotlinLogger.INSTANCE.logInfo("Registered NoOpIndentHelper stub")
            }
        }

        private fun registerEpIfAbsent(
            area: com.intellij.openapi.extensions.ExtensionsArea,
            name: String,
            beanClassName: String,
            kind: com.intellij.openapi.extensions.ExtensionPoint.Kind,
        ) {
            if (!area.hasExtensionPoint(name)) {
                area.registerExtensionPoint(name, beanClassName, kind)
                KotlinLogger.INSTANCE.logInfo("Registered extension point: $name")
            }
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

    /**
     * Updates the in-editor content of a source file so that the session's [KtFile] PSI
     * reflects the current (possibly unsaved) editor snapshot rather than the disk version.
     *
     * Finds the [SourceLightVirtualFile] for [path] in [fileMap], updates its in-memory
     * buffer via [LightVirtualFile.setContent], then syncs the IntelliJ [Document] and
     * commits it so the [KtFile] PSI is reparsed. No session rebuild required.
     *
     * If [path] is not in [fileMap], or the content is already up to date, this is a no-op.
     *
     * @param path absolute path of the source file (must be a key in [fileMap])
     * @param text current editor content
     */
    fun updateFileContent(path: String, text: String) {
        val lvf = fileMap[path] ?: return
        if (lvf.content.toString() == text) return
        lvf.setContent(null, text, true)
        // Transplant a freshly-parsed PSI tree from a non-physical KtFile into the session's
        // kaKtFile so that analysis offsets are always based on the current editor snapshot.
        //
        // A non-physical KtFile (isPhysical=false, eventSystemEnabled=false) does not fire
        // PsiTreeChangeEvents through the project MessageBus when its tree is loaded.  This
        // avoids the UnsupportedOperationException from MockComponentManager.createListener()
        // that occurs when any physical PSI event propagates in standalone mode.
        //
        // setTreeElementPointer() and FileElement.setPsi() are both public, event-free operations
        // that directly update the in-memory state of the KtFile without going through the bus.
        //
        // After the transplant, the K2 FIR cache still references the old PSI node identities.
        // Two caches must be invalidated for analyze {} to map FIR onto the new PSI nodes:
        //   1. LLFirSessionCache's source-session storage (holds the LLFirSession whose FIR was
        //      built from the old PSI)
        //   2. KaFirSessionProvider's own Caffeine Cache<KaModule, KaFirSession>
        //
        // In a real IDE both are cleared by SessionInvalidationListener (registered as
        // <projectListeners> on LLFirSessionInvalidationListener).  In standalone (MockProject)
        // mode lazy listener instantiation throws "Cannot create listener", so we clear both
        // caches directly.  Each step is wrapped in its own runCatching so a failure in one step
        // does not skip the next cache clear.
        val kaKtFile = getKtFileForPath(path) as? com.intellij.psi.impl.source.PsiFileImpl ?: return
        runCatching {
            val freshKtFile = org.jetbrains.kotlin.psi.KtPsiFactory(session.project).createFile(text)
            val freshTree = freshKtFile.calcTreeElement()
            kaKtFile.setTreeElementPointer(freshTree)
            freshTree.setPsi(kaKtFile)
        }.onFailure { ex ->
            KotlinLogger.INSTANCE.logWarning(
                "KotlinAnalysisAPISession.updateFileContent: tree transplant failed for $path: $ex"
            )
        }

        // Step 1: LLFirSessionCache source storage.  We clear the source-session storage directly
        // rather than calling LLFirSessionInvalidationService.invalidateAll(), which publishes a
        // SESSION_INVALIDATION event in a `finally` block.  In standalone mode that publish fails
        // (MockComponentManager.createListener() throws for the lazily-registered listener) and
        // logs a swallowed-but-noisy ERROR stack on every edit.  clear() removes and disposes all
        // cached source sessions without publishing any event.
        @OptIn(org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals::class)
        runCatching {
            org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache
                .getInstance(session.project)
                .storage.sourceCache.clear("updateFileContent")
        }.onFailure { ex ->
            KotlinLogger.INSTANCE.logWarning(
                "KotlinAnalysisAPISession.updateFileContent: LLFirSessionCache clear failed for $path: $ex"
            )
        }

        // Step 2: KaFirSessionProvider's own Caffeine cache.  analyze {} retrieves the KaFirSession
        // from this cache; without clearing it, the next analyze() returns a session whose FIR is
        // still bound to the old PSI node identities → "Classifier was found in KtFile but not in FirFile".
        @OptIn(KaImplementationDetail::class)
        runCatching {
            org.jetbrains.kotlin.analysis.api.session.KaSessionProvider
                .getInstance(session.project)
                .clearCaches()
        }.onFailure { ex ->
            KotlinLogger.INSTANCE.logWarning(
                "KotlinAnalysisAPISession.updateFileContent: KaSessionProvider.clearCaches() failed: $ex"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * Walks every source root recursively and creates a [SourceLightVirtualFile] for each
 * `.kt` and `.java` source file, reading its current content from disk.
 *
 * Java files are included so that the K2 session can resolve cross-language references
 * (Kotlin calling Java code defined in the same source root).
 */
private fun scanSourceFiles(sourceRoots: List<Path>): List<SourceLightVirtualFile> {
    val result = mutableListOf<SourceLightVirtualFile>()
    for (root in sourceRoots) {
        if (!Files.isDirectory(root)) continue
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val name = file.fileName.toString()
                val fileType: FileType = when {
                    name.endsWith(".kt")   -> KotlinFileType.INSTANCE
                    name.endsWith(".java") -> JavaFileType.INSTANCE
                    else                   -> return@forEach
                }
                runCatching {
                    val content = Files.readString(file)
                    result += SourceLightVirtualFile(name, fileType, content, file.toString())
                }.onFailure { ex ->
                    KotlinLogger.INSTANCE.logWarning(
                        "KotlinAnalysisAPISession: failed to read source file $file: $ex"
                    )
                }
            }
        }
    }
    return result
}
