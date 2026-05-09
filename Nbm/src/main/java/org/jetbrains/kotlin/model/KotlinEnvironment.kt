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

import com.intellij.codeInsight.ExternalAnnotationsManager
import com.intellij.codeInsight.InferredAnnotationsManager
import org.jetbrains.kotlin.resolve.lang.kotlin.NetBeansVirtualFileFinderFactory
import java.io.File
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport
import org.jetbrains.kotlin.cli.jvm.compiler.CliLightClassGenerationSupport
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.resolve.CodeAnalyzerInitializer
import com.intellij.codeInsight.ContainerProvider
import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInsight.runner.JavaMainMethodProvider
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.core.CoreJavaFileManager
import com.intellij.core.JavaCoreProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiManager
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.lang.jvm.facade.JvmElementProvider
import com.intellij.psi.impl.PsiTreeChangePreprocessor
import com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy
import com.intellij.psi.impl.file.impl.JavaFileManager
import org.jetbrains.kotlin.filesystem.KotlinLightClassManager
import org.jetbrains.kotlin.resolve.BuiltInsReferenceResolver
import org.jetbrains.kotlin.resolve.KotlinCacheServiceImpl
import org.jetbrains.kotlin.resolve.KotlinSourceIndex
import org.jetbrains.kotlin.utils.ProjectUtils
import org.jetbrains.kotlin.utils.KotlinImportInserterHelper
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.cli.common.CliModuleVisibilityManagerImpl
// ClassBuilderInterceptorExtension import removed — kotlin-compiler 1.9 marks it as a K1-only
// hard error; the corresponding registration in getExtensionsFromCommonXml() is now a no-op.
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import com.intellij.formatting.KotlinLanguageCodeStyleSettingsProvider
import com.intellij.formatting.KotlinSettingsProvider
import java.net.URLDecoder
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.cli.jvm.index.JvmDependenciesIndexImpl
import org.jetbrains.kotlin.cli.jvm.compiler.MockExternalAnnotationsManager
import org.jetbrains.kotlin.cli.jvm.compiler.MockInferredAnnotationsManager
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JvmAnalysisFlags
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.util.ImportInsertHelper
import org.jetbrains.kotlin.js.resolve.diagnostics.DefaultErrorMessagesJs
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.cli.jvm.compiler.CliTraceHolder
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticSuppressor
import org.jetbrains.kotlin.resolve.jvm.diagnostics.DefaultErrorMessagesJvm
import org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension
import org.netbeans.api.project.Project as NBProject
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCliJavaFileManagerImpl
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver
import org.jetbrains.kotlin.resolve.ModuleAnnotationsResolver
import org.jetbrains.kotlin.cli.jvm.compiler.CliModuleAnnotationsResolver
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.cli.jvm.index.SingleJavaFileRootsIndex

// Copied from kotlin eclipse plugin to avoid "Could not find installation home path".
// Touches SystemInfo to detect Windows; on JDK 17+ with core 232+ SystemInfo's version-parsing
// throws IllegalArgumentException for version strings like "25.0.1" (Java 25). Use a plain
// System.getProperty("os.name") check instead — no static init of SystemInfo triggered.
private fun setIdeaIoUseFallback() {
    val osName = System.getProperty("os.name", "").lowercase()
    if (osName.contains("win")) {
        System.getProperties().setProperty("idea.io.use.nio2", java.lang.Boolean.TRUE.toString())
    }
}

class KotlinEnvironment private constructor(kotlinProject: NBProject, disposable: Disposable) {

    companion object {
        val CACHED_ENVIRONMENT = hashMapOf<NBProject, KotlinEnvironment>()
        
        @Synchronized fun getEnvironment(kotlinProject: NBProject): KotlinEnvironment {
            if (!CACHED_ENVIRONMENT.containsKey(kotlinProject)) {
                CACHED_ENVIRONMENT.put(kotlinProject, KotlinEnvironment(kotlinProject, Disposer.newDisposable()))
            }
            
            return CACHED_ENVIRONMENT[kotlinProject]!!
        }
        
        @Synchronized fun updateKotlinEnvironment(kotlinProject: NBProject) = getEnvironment(kotlinProject).configureClasspath(kotlinProject)
    }

    val applicationEnvironment: CoreApplicationEnvironment
    private val projectEnvironment: JavaCoreProjectEnvironment
    val project: MockProject
    val roots = hashSetOf<JavaRoot>()
    
    val index by lazy { JvmDependenciesIndexImpl(roots.toList()) }
    
    val configuration = CompilerConfiguration()
    
    init {
        val startTime = System.nanoTime()

        setIdeaIoUseFallback()
                
        applicationEnvironment = createJavaCoreApplicationEnvironment(disposable)
        projectEnvironment = object : JavaCoreProjectEnvironment(disposable, applicationEnvironment) {
            override fun preregisterServices() {
                // Extensions.getArea(Project) removed in core 232+; ProjectImpl exposes
                // extensionArea directly. CoreProjectEnvironment.project is a MockProject
                // / ProjectImpl that has it.
                registerProjectExtensionPoints(project.extensionArea)
            }
            
            override fun createCoreFileManager() = KotlinCliJavaFileManagerImpl(PsiManager.getInstance(project))
        }
        project = projectEnvironment.project
        
        with (project) {
            registerService(ModuleVisibilityManager::class.java, CliModuleVisibilityManagerImpl(true))
            registerService(NullableNotNullManager::class.java, KotlinNullableNotNullManager(kotlinProject, project))
            registerService(CoreJavaFileManager::class.java,
                ServiceManager.getService(project, JavaFileManager::class.java) as CoreJavaFileManager)
            
            val cliTraceHolder = CliTraceHolder()
            val cliLightClassGenerationSupport = CliLightClassGenerationSupport(cliTraceHolder, project)
            registerService(LightClassGenerationSupport::class.java, cliLightClassGenerationSupport)
            registerService(CliLightClassGenerationSupport::class.java, cliLightClassGenerationSupport)
            registerService(CodeAnalyzerInitializer::class.java, cliTraceHolder)
            registerService(KotlinLightClassManager::class.java, KotlinLightClassManager(kotlinProject))
            registerService(ModuleAnnotationsResolver::class.java, CliModuleAnnotationsResolver())
            registerService(KotlinJavaPsiFacade::class.java, KotlinJavaPsiFacade(this))
            registerService(JavaModuleResolver::class.java, object : JavaModuleResolver {
                override fun checkAccessibility(
                    fileFromOurModule: com.intellij.openapi.vfs.VirtualFile?,
                    referencedFile: com.intellij.openapi.vfs.VirtualFile,
                    referencedPackage: org.jetbrains.kotlin.name.FqName?
                ): JavaModuleResolver.AccessError? = null

                // Inherited from JavaModuleAnnotationsProvider in 1.9 — Nbm doesn't track JPMS module
                // annotations, so report none.
                override fun getAnnotationsForModuleOwnerOfClass(
                    classId: org.jetbrains.kotlin.name.ClassId
                ): List<org.jetbrains.kotlin.load.java.structure.JavaAnnotation>? = null
            })
            registerService(BuiltInsReferenceResolver::class.java, BuiltInsReferenceResolver(project))
            registerService(KotlinSourceIndex::class.java, KotlinSourceIndex())
            registerService(KotlinCacheService::class.java, KotlinCacheServiceImpl(project, kotlinProject))
            registerService(VirtualFileFinderFactory::class.java, NetBeansVirtualFileFinderFactory(kotlinProject))
            registerService(ImportInsertHelper::class.java, KotlinImportInserterHelper())

            registerService(ExternalAnnotationsManager::class.java, MockExternalAnnotationsManager())
            registerService(InferredAnnotationsManager::class.java, MockInferredAnnotationsManager())
            // 232+ requires JavaElementSourceFactory as project service — used by
            // kotlin-compiler:1.9.25's resolution code in JavaElementImpl. JavaFixedElementSourceFactory
            // is the default impl shipped inside kotlin-compiler.
            registerService(
                org.jetbrains.kotlin.load.java.structure.impl.source.JavaElementSourceFactory::class.java,
                org.jetbrains.kotlin.load.java.structure.impl.source.JavaFixedElementSourceFactory()
            )
        }
        
        configuration.put<String>(CommonConfigurationKeys.MODULE_NAME, project.name)
        configuration.put(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS,
            LanguageVersionSettingsImpl(LanguageVersion.KOTLIN_1_3, ApiVersion.KOTLIN_1_3,
                mapOf(JvmAnalysisFlags.suppressMissingBuiltinsError to true)))

        configureClasspath(kotlinProject)

        val javaFileManager = ServiceManager.getService(project, JavaFileManager::class.java) as KotlinCliJavaFileManagerImpl
        javaFileManager.initialize(
            index,
            emptyList(),
            SingleJavaFileRootsIndex(emptyList()),
            true
        )

        ExpressionCodegenExtension.Companion.registerExtensionPoint(project)
        
        getExtensionsFromCommonXml()
        getExtensionsFromKotlin2JvmXml()
        
        CACHED_ENVIRONMENT.put(kotlinProject, this)
        KotlinLogger.INSTANCE.logInfo("KotlinEnvironment init: ${(System.nanoTime() - startTime)} ns")
    }
    
    private fun registerProjectExtensionPoints(area: ExtensionsArea) {
        // EP_NAME → EP for PsiTreeChangePreprocessor and PsiElementFinder in core 232+; new fields
        // are ProjectExtensionPointName, not ExtensionPointName. Reconstruct by string name.
        CoreApplicationEnvironment.registerExtensionPoint(area,
                ExtensionPointName.create<PsiTreeChangePreprocessor>(PsiTreeChangePreprocessor.EP.name), PsiTreeChangePreprocessor::class.java)
        CoreApplicationEnvironment.registerExtensionPoint(area,
                ExtensionPointName.create<PsiElementFinder>(PsiElementFinder.EP.name), PsiElementFinder::class.java)
        CoreApplicationEnvironment.registerExtensionPoint(area,
                JvmElementProvider.EP_NAME, JvmElementProvider::class.java)
    }
    
    private fun getExtensionsFromCommonXml() {
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                ExtensionPointName("org.jetbrains.kotlin.diagnosticSuppressor"), DiagnosticSuppressor::class.java)
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                ExtensionPointName("org.jetbrains.kotlin.defaultErrorMessages"), DefaultErrorMessages.Extension::class.java)
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                ExtensionPointName(("org.jetbrains.kotlin.expressionCodegenExtension")), ExpressionCodegenExtension::class.java)
        // classBuilderFactoryInterceptorExtension registration removed: ClassBuilderInterceptorExtension
        // is now a hard-error K1-only API in kotlin-compiler 1.9. No replacement registered — the
        // analyzer-only path Nbm uses doesn't generate bytecode.
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
                ExtensionPointName(("org.jetbrains.kotlin.packageFragmentProviderExtension")), PackageFragmentProviderExtension::class.java)
        CoreApplicationEnvironment.registerApplicationExtensionPoint(CodeStyleSettingsProvider.EXTENSION_POINT_NAME, KotlinSettingsProvider::class.java)
        CoreApplicationEnvironment.registerApplicationExtensionPoint(LanguageCodeStyleSettingsProvider.EP_NAME, KotlinLanguageCodeStyleSettingsProvider::class.java)
        
        with (Extensions.getRootArea()) {
            getExtensionPoint(CodeStyleSettingsProvider.EXTENSION_POINT_NAME).registerExtension(KotlinSettingsProvider())
            getExtensionPoint(LanguageCodeStyleSettingsProvider.EP_NAME).registerExtension(KotlinLanguageCodeStyleSettingsProvider())
        }
    }
    
    private fun getExtensionsFromKotlin2JvmXml() {
        Extensions.getRootArea()
            .getExtensionPoint<DefaultErrorMessages.Extension>(ExtensionPointName("org.jetbrains.kotlin.defaultErrorMessages"))
            .registerExtension(DefaultErrorMessagesJvm())
    }
    
    fun configureClasspath(kotlinProject: NBProject) {
        val classpath = ProjectUtils.getClasspath(kotlinProject)
        KotlinLogger.INSTANCE.logInfo("Project ${kotlinProject.projectDirectory.path} classpath is $classpath")
        classpath.forEach {
            if (it.endsWith("!/")) {
                addToClasspath(it.split("!/")[0].substringAfter("file:"), null)
            } else {
                addToClasspath(it, null)
            }
        }
    }
    
    private fun createJavaCoreApplicationEnvironment(disposable: Disposable): CoreApplicationEnvironment {
        val env = KotlinCoreApplicationEnvironment.create(disposable, false)
        registerAppExtensionPoints()

        with (env) {
            registerFileType(PlainTextFileType.INSTANCE, "xml")
            registerFileType(KotlinFileType.INSTANCE, "kt")
            registerParserDefinition(KotlinParserDefinition())
            application.registerService(KotlinBinaryClassCache::class.java, KotlinBinaryClassCache())
        }

        return env
    }

    private fun registerAppExtensionPoints() {
        // KotlinCoreApplicationEnvironment already registers ContainerProvider, ClassFileDecompilers,
        // PsiAugmentProvider, JavaMainMethodProvider — only register what it doesn't
        CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ClsCustomNavigationPolicy.EP_NAME,
                ClsCustomNavigationPolicy::class.java)
    }
    
    private fun addToClasspath(path: String, rootType: JavaRoot.RootType?) {
        val file = File(path)
        if (file.isFile) {
            val jarFile = applicationEnvironment.jarFileSystem.findFileByPath("$path!/") ?: return
            projectEnvironment.addJarToClassPath(file)
            val type = rootType ?: JavaRoot.RootType.BINARY
            
            roots.add(JavaRoot(jarFile, type, null))
        } else {
            val root = applicationEnvironment.localFileSystem.findFileByPath(path) ?: return
            projectEnvironment.addSourcesToClasspath(root)
            val type = rootType ?: JavaRoot.RootType.SOURCE
            
            roots.add(JavaRoot(root, type, null))
        }
    }
    
    fun getVirtualFile(location: String) = applicationEnvironment.localFileSystem.findFileByPath(location)

    fun getVirtualFileInJar(pathToJar: String, relativePath: String): VirtualFile? {
        val decodedPathToJar = URLDecoder.decode(pathToJar, "UTF-8") ?: pathToJar
        val decodedRelativePath = URLDecoder.decode(relativePath, "UTF-8") ?: relativePath
        
        return applicationEnvironment.jarFileSystem.findFileByPath("$decodedPathToJar!/$decodedRelativePath")
    }
}