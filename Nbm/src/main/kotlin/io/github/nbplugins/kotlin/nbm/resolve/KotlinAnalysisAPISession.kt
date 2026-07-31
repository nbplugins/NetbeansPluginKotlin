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
import org.openide.filesystems.FileUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Manages a K2 Analysis API session ([StandaloneAnalysisAPISession]) for a NetBeans project.
 *
 * The ordinary session, created by [getSession], contains only the owning project's sources so it
 * remains safe to create from NetBeans parsing and indexing tasks. Build-wide refactorings obtain a
 * separate writable session through [getBuildScopedSession]; Maven and Gradle sibling sources never
 * enter the ordinary editor-analysis session.
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
    private constructor(nbProject: NBProject, buildScoped: Boolean = false) : this(
        moduleName = nbProject.projectDirectory.name,
        // Preserve the owner's normal dependency classpath. Passing build-wide roots here would
        // classify sibling compiled output as a second copy of the same source declarations.
        binaryJars = collectBinaryJars(nbProject, collectSourceRoots(nbProject)),
        sourceRoots = if (buildScoped) collectBuildSourceRoots(nbProject) else collectSourceRoots(nbProject),
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

        // Sibling Gradle-subproject Kotlin source: a directory binary root that maps to a
        // sibling module's real Kotlin source (`<module>/src/<sourceSet>/kotlin`) is added as
        // an additional K2 *source* module instead of only its compiled binary output — a
        // binary-only symbol has no attached PSI, so Ctrl+Click "Go to Declaration" into it
        // silently fails even though hover/completion (which only need the resolved symbol,
        // not its PSI) work fine. The corresponding binary directory is dropped once real
        // source is available, to avoid registering the same declarations twice.
        val siblingSourceRoots = binaryJars.filter { it.toFile().isDirectory }
            .mapNotNull { siblingKotlinSourceRootOf(it) }
            .distinct()
        val effectiveBinaryJars = binaryJars.filterNot { siblingKotlinSourceRootOf(it) != null }
        KotlinLogger.INSTANCE.logInfo(
            "KotlinAnalysisAPISession '$moduleName': sibling Kotlin source roots (${siblingSourceRoots.size}) = $siblingSourceRoots"
        )

        // Scan source roots and create in-memory LVFs so that updateFileContent() can keep
        // the session's KtFile PSI in sync with live editor snapshots without rebuilding.
        val tScan = System.nanoTime()
        val lvfs = scanSourceFiles(sourceRoots)
        fileMap = lvfs.associateBy { it.path }
        KotlinLogger.INSTANCE.logInfo(
            "[PERF] KotlinAnalysisAPISession '$moduleName': scanSourceFiles=${(System.nanoTime() - tScan) / 1_000_000}ms (${lvfs.size} files)"
        )
        val siblingLvfs = scanSourceFiles(siblingSourceRoots)

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
                val libModules = effectiveBinaryJars.map { jar ->
                    addModule(buildKtLibraryModule {
                        libraryName = jar.fileName.toString()
                        addBinaryRoot(jar)
                        platform = JvmPlatforms.unspecifiedJvmPlatform
                    })
                }
                KotlinLogger.INSTANCE.logInfo(
                    "[PERF] KotlinAnalysisAPISession '$moduleName': library modules=${(System.nanoTime() - tLibs) / 1_000_000}ms (${effectiveBinaryJars.size} jars)"
                )

                val siblingSourceModule = if (siblingLvfs.isEmpty()) null else addModule(buildKtSourceModule {
                    this.moduleName = "$moduleName-siblings"
                    languageVersionSettings = LanguageVersionSettingsImpl(
                        LanguageVersion.KOTLIN_2_0, ApiVersion.KOTLIN_2_0
                    )
                    siblingLvfs.forEach { addSourceVirtualFile(it) }
                    addRegularDependency(jdkModule)
                    libModules.forEach { addRegularDependency(it) }
                    platform = JvmPlatforms.unspecifiedJvmPlatform
                })

                val tSrc = System.nanoTime()
                addModule(buildKtSourceModule {
                    this.moduleName = moduleName
                    languageVersionSettings = LanguageVersionSettingsImpl(
                        LanguageVersion.KOTLIN_2_0, ApiVersion.KOTLIN_2_0
                    )
                    lvfs.forEach { addSourceVirtualFile(it) }
                    addRegularDependency(jdkModule)
                    libModules.forEach { addRegularDependency(it) }
                    siblingSourceModule?.let { addRegularDependency(it) }
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
        installNoOpPsiSearchHelper(session)

        val tProvider = System.nanoTime()
        installLiveDeclarationProvider(session)
        KotlinLogger.INSTANCE.logInfo(
            "[PERF] KotlinAnalysisAPISession '$moduleName': installLiveDeclarationProvider=${(System.nanoTime() - tProvider) / 1_000_000}ms"
        )

        KotlinLogger.INSTANCE.logInfo(
            "[PERF] KotlinAnalysisAPISession '$moduleName': TOTAL=${(System.nanoTime() - startTime) / 1_000_000}ms " +
            "(${binaryJars.size} binary jars, ${sourceRoots.size} source roots, ${lvfs.size} source files)"
        )

        // Registered by IntelliJ Project instance (not just NBProject) so that code operating on a
        // bare PsiElement/Project — e.g. KotlinMoveUsageSearchServiceImpl, which only receives a
        // KtNamedDeclaration, not an NBProject — can find the session that owns it. Needed for
        // both the normal getSession(nbProject) path and standalone sessions built via
        // createWithJars() (used by tests), which aren't NBProject-keyed at all.
        byProject[session.project] = this
        // The active session determines the standalone hierarchy-search scope. Ordinary sessions
        // contain only their owner; the explicit Push Members Down build session installs a bridge
        // over the owner plus sibling build modules.
        org.jetbrains.kotlin.idea.searching.inheritors.StandaloneInheritorSearch.install(
            io.github.nbplugins.kotlin.nbm.refactoring.KotlinStandaloneInheritorSearch(this),
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

    /**
     * Registers a [NoOpPsiSearchHelper] on the session's project. IDEA refactorings
     * (e.g. Inline Variable's `AbstractKotlinInlinePropertyProcessor.extractInitialization`)
     * dispatch to `PsiSearchHelper.getInstance(project).processRequests(...)` even when no
     * `referencesSearch` extensions are registered. Without a service, the call throws
     * `@NotNull method ... must not return null`. The stub returns empty results so the
     * "initializer-in-declaration" branch of `extractInitialization` is taken cleanly.
     */
    private fun installNoOpPsiSearchHelper(session: StandaloneAnalysisAPISession) {
        val project = session.project
        val mock = project as? com.intellij.mock.MockComponentManager ?: return
        if (project.getService(com.intellij.psi.search.PsiSearchHelper::class.java) == null) {
            mock.registerService(com.intellij.psi.search.PsiSearchHelper::class.java, NoOpPsiSearchHelper())
            KotlinLogger.INSTANCE.logInfo("Registered NoOpPsiSearchHelper for project ${project.name}")
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
        private val buildScopedCache = hashMapOf<NBProject, KotlinAnalysisAPISession>()

        /** Secondary index by the underlying IntelliJ [com.intellij.openapi.project.Project] — see [forProject]. */
        private val byProject = hashMapOf<com.intellij.openapi.project.Project, KotlinAnalysisAPISession>()

        /**
         * Returns the [KotlinAnalysisAPISession] that owns [project], if any.
         *
         * Unlike [getSession] (keyed by NetBeans' own project type), this works for standalone
         * sessions built via [createWithJars] too (used by tests), and for callers that only have
         * a bare PSI element/`Project` in hand rather than an [NBProject] — e.g.
         * `KotlinMoveUsageSearchServiceImpl`, which is only given a `KtNamedDeclaration`.
         */
        fun forProject(project: com.intellij.openapi.project.Project): KotlinAnalysisAPISession? = byProject[project]

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
            LenientLoggerFactory.install()
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
            // `org.jetbrains.kotlin.postInsertDeclarationCallback` is queried (via
            // `forEachExtensionSafe`, which requires the EP to exist even with zero registered
            // extensions) by the real IDEA Extract Function generator
            // (`ExtractFunctionGenerator.insertDeclaration`, E9 Phase 2) after inserting the
            // extracted declaration. No NetBeans implementation is needed — registering the EP
            // empty makes the callback loop a no-op, matching upstream behaviour when no plugin
            // contributes one.
            registerEpIfAbsent(area, "org.jetbrains.kotlin.postInsertDeclarationCallback",
                "org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.PostInsertDeclarationCallback",
                com.intellij.openapi.extensions.ExtensionPoint.Kind.INTERFACE)
            // `com.intellij.referencesSearch` is consumed by IDEA refactorings (e.g. Inline Variable's
            // `AbstractKotlinInlinePropertyProcessor.extractInitialization` calls
            // `ReferencesSearchScopeHelper.search`). Registering the EP without any extensions is
            // sufficient for `val`-with-initializer cases — the search returns an empty result and
            // the initializer-in-declaration branch is taken. Declared in `platform/indexing-api/resources/META-INF/Indexing.xml`.
            registerEpIfAbsent(area, "com.intellij.referencesSearch",
                "com.intellij.util.QueryExecutor",
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
            // ShortenReferencesFacility — called by InlinePostProcessor.shortenReferences at the
            // end of an Inline Variable / Inline Function refactoring. The implementation
            // (SymbolBasedShortenReferencesFacility) lives in the KotlinRefactoring jar and
            // delegates to the K2-aware shortenReferences[InRange] helpers.
            if (app.getService(org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility::class.java) == null) {
                app.registerService(
                    org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility::class.java,
                    io.github.nbplugins.kotlin.refactoring.KotlinSymbolBasedShortenReferencesFacility::class.java,
                )
                KotlinLogger.INSTANCE.logInfo("Registered KotlinSymbolBasedShortenReferencesFacility")
            }
            // KotlinNameValidatorProvider — used by KotlinNameSuggestionProvider during Extract
            // Function parameter-name suggestion (via parametersUtil.kt → KotlinDeclarationNameValidator).
            if (app.getService(org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameValidatorProvider::class.java) == null) {
                app.registerService(
                    org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameValidatorProvider::class.java,
                    io.github.nbplugins.kotlin.refactoring.KotlinNameValidatorProviderImpl::class.java,
                )
                KotlinLogger.INSTANCE.logInfo("Registered KotlinNameValidatorProviderImpl")
            }
            // KtReferenceMutateService — performs reference rebinding (KtSimpleNameReference.bindToElement).
            // Required by the Copy Declaration engine (K2MoveRenameUsageInfo.Source.retarget) to
            // requalify references in a copied declaration before shortening re-adds the needed imports.
            // The K2 implementation is compiled into KotlinRefactoring (patched to be public).
            if (app.getService(org.jetbrains.kotlin.idea.references.KtReferenceMutateService::class.java) == null) {
                app.registerService(
                    org.jetbrains.kotlin.idea.references.KtReferenceMutateService::class.java,
                    org.jetbrains.kotlin.idea.k2.refactoring.K2ReferenceMutateService::class.java,
                )
                KotlinLogger.INSTANCE.logInfo("Registered K2ReferenceMutateService")
            }
            // KotlinMoveUsageSearchService — real project-wide reference search backing Move
            // Declaration's (E9.7) external-usage retargeting (K2MoveRenameUsageInfo needs
            // ReferencesSearch, a no-op standalone via NoOpPsiSearchHelper).
            if (app.getService(org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService::class.java) == null) {
                app.registerService(
                    org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService::class.java,
                    io.github.nbplugins.kotlin.nbm.refactoring.KotlinMoveUsageSearchServiceImpl::class.java,
                )
                KotlinLogger.INSTANCE.logInfo("Registered KotlinMoveUsageSearchServiceImpl")
            }
            // KotlinChangeSignatureUsageSearchService — real project-wide reference search backing
            // Change Signature's (E9.8) call-site/parameter-reference retargeting (the ported engine
            // expects ReferencesSearch/MethodReferencesSearch, a no-op standalone).
            if (app.getService(org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageSearchService::class.java) == null) {
                app.registerService(
                    org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageSearchService::class.java,
                    io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangeSignatureUsageSearchServiceImpl::class.java,
                )
                KotlinLogger.INSTANCE.logInfo("Registered KotlinChangeSignatureUsageSearchServiceImpl")
            }
            registerExtractSuperServices(app)
        }

        /** Registers the K2 services and Kotlin language extension required by E9.15/E9.16. */
        private fun registerExtractSuperServices(app: MockApplication) {
            if (app.getService(org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfoSupport::class.java) == null) {
                app.registerService(
                    org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfoSupport::class.java,
                    org.jetbrains.kotlin.idea.k2.refactoring.memberInfo.K2MemberInfoSupport::class.java,
                )
            }
            if (app.getService(org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfoStorageSupport::class.java) == null) {
                app.registerService(
                    org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfoStorageSupport::class.java,
                    org.jetbrains.kotlin.idea.k2.refactoring.pullUp.K2MemberInfoStorageSupport::class.java,
                )
            }
            val helpers = com.intellij.refactoring.memberPullUp.PullUpHelper.INSTANCE
            if (helpers.forLanguage(org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE) == null) {
                helpers.addExplicitExtension(
                    org.jetbrains.kotlin.idea.KotlinLanguage.INSTANCE,
                    org.jetbrains.kotlin.idea.k2.refactoring.pullUp.K2PullUpHelperFactory(),
                )
                KotlinLogger.INSTANCE.logInfo("Registered K2PullUpHelperFactory for Kotlin")
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
         * Returns the K2 session containing the owner and writable Kotlin sources from its related
         * Maven reactor or Gradle build. This is intentionally opt-in for build-wide refactorings;
         * parser and indexing paths must use [getSession].
         *
         * @param nbProject the project that owns the refactoring source declaration
         * @return a cached build-scoped session for the owner project
         */
        @Synchronized
        fun getBuildScopedSession(nbProject: NBProject): KotlinAnalysisAPISession =
            buildScopedCache.getOrPut(nbProject) { KotlinAnalysisAPISession(nbProject, buildScoped = true) }

        /**
         * Removes all cached sessions from the cache.
         *
         * Call when the plugin is unloaded or all projects are closed to release resources.
         * The next call to [getSession] will create a fresh instance.
         */
        @Synchronized
        fun disposeAll() {
            cache.clear()
            buildScopedCache.clear()
            byProject.clear()
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
            buildScopedCache.remove(nbProject)
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
         * Collects binary classpath roots (JARs and class-directories) from the project
         * classpath.
         *
         * Normalises each entry: strips a leading `file:` scheme and a trailing `!/`
         * (the NetBeans Maven integration returns `jar:file:/path/to.jar!/`-style strings
         * whose `.getPath()` still has the `file:` prefix and `!/` suffix).
         * Both `.jar` files and plain directories are included — Gradle's IDE-mode compile
         * classpath represents project-to-project (subproject) dependencies as a directory
         * of compiled `.class` files (e.g. `otherModule/build/classes/kotlin/main/`) rather
         * than a JAR, so restricting to `.jar` would silently drop every sibling-module
         * dependency's symbols from K2 resolution. Only entries that exist on disk are kept.
         *
         * Directory entries that coincide with (or contain/are contained by) one of
         * [sourceRoots] are excluded: some project types put their own compiled-output
         * directory on the COMPILE classpath, and adding that as a *binary* module alongside
         * the identical files already present as *source* confuses K2's module resolution
         * (`KaBaseIllegalPsiException` / module-mismatch errors).
         *
         * For a Kotlin-only Gradle subproject dependency, the compile classpath only ever
         * reports `<module>/build/classes/java/<sourceSet>/` (NetBeans's Gradle support is
         * Java-plugin-oriented and has no notion of the Kotlin plugin's own output directory),
         * which is empty for such a module. Each such directory therefore gets its
         * `build/classes/kotlin/<sourceSet>/` sibling added too, when it exists on disk.
         *
         * @param nbProject the NetBeans project
         * @param sourceRoots this project's own source roots, to exclude self-referential
         *        binary directory entries
         * @return list of existing binary root [Path]s (JAR files or class directories)
         */
        private fun collectBinaryJars(nbProject: NBProject, sourceRoots: List<Path>): List<Path> {
            val projectDirPath = FileUtil.toFile(nbProject.projectDirectory)?.toPath()
            val rawClasspath = ProjectUtils.getClasspath(nbProject)
            KotlinLogger.INSTANCE.logInfo(
                "collectBinaryJars '${nbProject.projectDirectory.name}': projectDirPath=$projectDirPath sourceRoots=$sourceRoots"
            )
            KotlinLogger.INSTANCE.logInfo(
                "collectBinaryJars '${nbProject.projectDirectory.name}': raw classpath (${rawClasspath.size} entries) = $rawClasspath"
            )
            val normalized = rawClasspath.map { raw ->
                var s = raw
                if (s.startsWith("file:")) s = s.removePrefix("file:")
                if (s.endsWith("!/")) s = s.removeSuffix("!/")
                s
            }
            val parsed = normalized.mapNotNull { runCatching { Path.of(it) }.getOrNull() }
            // Compute Kotlin-sibling candidates BEFORE the existence filter: a Kotlin-only
            // module's `build/classes/java/<sourceSet>/` entry may not exist on disk at all
            // (only its `build/classes/kotlin/<sourceSet>/` sibling does), so the sibling must
            // be considered on its own merits rather than only for entries that already exist.
            val withKotlinSiblings = parsed
                .flatMap { path ->
                    val kotlinSibling = kotlinClassesSiblingOf(path)
                    if (kotlinSibling != null) listOf(path, kotlinSibling) else listOf(path)
                }
                .distinct()
            val existing = withKotlinSiblings.filter { path ->
                val f = path.toFile()
                f.isDirectory || (f.isFile && path.toString().endsWith(".jar"))
            }
            KotlinLogger.INSTANCE.logInfo(
                "collectBinaryJars '${nbProject.projectDirectory.name}': after existence/type filter (${existing.size}/${withKotlinSiblings.size}); " +
                    "dropped = ${withKotlinSiblings.filterNot { existing.contains(it) }}"
            )
            val result = existing.filterNot { binaryRoot ->
                // Scoped to this project's own directory: a broad sourceRoots prefix match
                // (e.g. a source root at or above the whole multi-module reactor root) must
                // not cause sibling subprojects' own binary directories to look self-referential.
                val selfScoped = projectDirPath != null && binaryRoot.startsWith(projectDirPath)
                val overlapsSource = sourceRoots.any { src -> binaryRoot == src || binaryRoot.startsWith(src) || src.startsWith(binaryRoot) }
                if (selfScoped && overlapsSource) {
                    KotlinLogger.INSTANCE.logInfo(
                        "collectBinaryJars '${nbProject.projectDirectory.name}': excluding self-referential binary root $binaryRoot"
                    )
                }
                selfScoped && overlapsSource
            }
            KotlinLogger.INSTANCE.logInfo(
                "collectBinaryJars '${nbProject.projectDirectory.name}': resolved binary roots (${result.size}) = $result"
            )
            return result
        }

        /**
         * For a Gradle Java-source-set output directory (`.../build/classes/java/<sourceSet>/`),
         * returns the corresponding Kotlin output directory
         * (`.../build/classes/kotlin/<sourceSet>/`) if it exists on disk. Returns `null` for
         * paths that don't match that shape, or when no such Kotlin directory exists.
         *
         * @param path a binary classpath directory entry
         * @return the sibling Kotlin output directory, or `null`
         */
        private fun kotlinClassesSiblingOf(path: Path): Path? {
            val marker = "${File.separatorChar}classes${File.separatorChar}java${File.separatorChar}"
            val s = path.toString()
            val idx = s.lastIndexOf(marker)
            if (idx < 0) return null
            val kotlinPath = Path.of(
                s.substring(0, idx) + "${File.separatorChar}classes${File.separatorChar}kotlin${File.separatorChar}" +
                    s.substring(idx + marker.length)
            )
            return kotlinPath.takeIf { it.toFile().isDirectory }
        }

        /**
         * For a Gradle module's compiled-output directory (`.../<module>/build/classes/<lang>/<sourceSet>/`,
         * for any `<lang>` — `java`, `kotlin`, etc.), returns that module's actual Kotlin source
         * directory (`.../<module>/src/<sourceSet>/kotlin/`) if it exists on disk. Returns `null`
         * for paths that don't match that shape, or when no such source directory exists.
         *
         * Used to upgrade a sibling Gradle subproject's *binary* classpath directory into a real
         * K2 *source* module, so Ctrl+Click navigation into it has PSI to land on.
         *
         * @param path a binary classpath directory entry
         * @return the sibling module's Kotlin source directory, or `null`
         */
        private fun siblingKotlinSourceRootOf(path: Path): Path? {
            val marker = "${File.separatorChar}build${File.separatorChar}classes${File.separatorChar}"
            val s = path.toString()
            val idx = s.indexOf(marker)
            if (idx < 0) return null
            val rest = s.substring(idx + marker.length).split(File.separatorChar).filter { it.isNotEmpty() }
            if (rest.size < 2) return null
            val sourceSet = rest[1]
            val moduleBase = s.substring(0, idx)
            val candidate = Path.of(moduleBase, "src", sourceSet, "kotlin")
            return candidate.takeIf { it.toFile().isDirectory }
        }

        /**
         * Collects source root paths from the project's SOURCE classpath.
         *
         * @param nbProject the NetBeans project
         * @return list of source root [Path]s, or an empty list if none are available
         */
        private fun collectBuildSourceRoots(nbProject: NBProject): List<Path> =
            BuildProjectScope.relatedProjects(nbProject)
                .flatMap(::collectSourceRoots)
                .distinct()

        /**
         * Collects source root paths for exactly one NetBeans project.
         *
         * [collectBuildSourceRoots] combines this result across sibling modules when a refactoring
         * needs build-wide PSI; the ordinary classpath query remains isolated here so unrelated
         * open projects cannot enter the K2 source module.
         *
         * @param nbProject one project belonging to the active build.
         * @return source root paths, or an empty list if none are available.
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
