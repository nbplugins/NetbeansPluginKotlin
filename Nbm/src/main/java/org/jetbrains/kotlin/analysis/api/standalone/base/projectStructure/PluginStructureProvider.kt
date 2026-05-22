/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 *
 * Adapted for IntelliJ Platform 253: PathResolver.resolvePath() changed from
 * 4-arg (ReadModuleContext, DataLoader, String, RawPluginDescriptor?) to
 * 3-arg (PluginDescriptorReaderContext, DataLoader, String): PluginDescriptorBuilder.
 * RawPluginDescriptor API also changed (appContainerDescriptor→appElementsContainer, etc.).
 * Source: kotlin/analysis/.../PluginStructureProvider.kt, adapted for platform 253.33514.17.
 */
package org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.ide.plugins.DataLoader
import com.intellij.ide.plugins.PluginXmlPathResolver
import com.intellij.mock.MockApplication
import com.intellij.mock.MockComponentManager
import com.intellij.mock.MockProject
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.platform.plugins.parser.impl.PluginDescriptorBuilder
import com.intellij.platform.plugins.parser.impl.PluginDescriptorReaderContext
import com.intellij.platform.plugins.parser.impl.RawPluginDescriptor
import com.intellij.platform.plugins.parser.impl.ScopedElementsContainer
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.messages.ListenerDescriptor
import com.intellij.util.messages.impl.MessageBusEx
import com.intellij.util.messages.impl.PluginListenerDescriptor
import com.intellij.util.xml.dom.NoOpXmlInterner
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.platform.analysisMessageBus
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@Suppress("UnstableApiUsage")
@KaImplementationDetail
object PluginStructureProvider {
    private val fakePluginDescriptor = DefaultPluginDescriptor("analysis-api-standalone-base-loader")

    private object ReadContext : PluginDescriptorReaderContext {
        override val interner get() = NoOpXmlInterner
        override val isMissingIncludeIgnored: Boolean get() = false
    }

    private class ResourceDataLoader(val classLoader: ClassLoader) : DataLoader {
        override fun load(path: String, pluginDescriptorSourceOnly: Boolean): InputStream? =
            classLoader.getResource(path)?.openStream()
        override fun toString(): String = "resources data loader"
    }

    private val pluginDescriptorsCache =
        ContainerUtil.createConcurrentSoftKeySoftValueMap<PluginDesignation, RawPluginDescriptor>()

    private data class PluginDesignation(val relativePath: String, val classLoader: ClassLoader) {
        constructor(relativePath: String, componentManager: MockComponentManager) :
            this(relativePath, componentManager.classLoader)
    }

    private fun getOrCalculatePluginDescriptor(designation: PluginDesignation): RawPluginDescriptor =
        pluginDescriptorsCache.computeIfAbsent(designation) {
            (PluginXmlPathResolver.DEFAULT_PATH_RESOLVER.resolvePath(
                ReadContext,
                ResourceDataLoader(designation.classLoader),
                designation.relativePath,
            ) ?: PluginDescriptorBuilder.Companion.builder()).build()
        }

    fun registerProjectExtensionPoints(project: MockProject, pluginRelativePath: String) {
        val pluginDescriptor = getOrCalculatePluginDescriptor(PluginDesignation(pluginRelativePath, project))
        for (ep in pluginDescriptor.projectElementsContainer.extensionPoints) {
            val epName = ep.qualifiedName ?: ep.name ?: continue
            val className = ep.`interface` ?: ep.beanClass ?: continue
            CoreApplicationEnvironment.registerExtensionPoint(
                project.extensionArea,
                epName,
                project.loadClass<Any>(className, fakePluginDescriptor),
            )
        }
    }

    fun registerApplicationServices(application: MockApplication, pluginRelativePath: String) {
        val container = RawPluginDescriptor::appElementsContainer
        registerExtensionPoints(application, pluginRelativePath, container)
        registerExtensionPointImplementations(application, pluginRelativePath)
        registerServices(application, pluginRelativePath, container)
    }

    fun registerProjectServices(project: MockProject, pluginRelativePath: String) {
        val container = RawPluginDescriptor::projectElementsContainer
        registerExtensionPoints(project, pluginRelativePath, container)
        registerExtensionPointImplementations(project, pluginRelativePath)
        registerServices(project, pluginRelativePath, container)
        registerProjectListeners(project, pluginRelativePath)
    }

    fun registerProjectListeners(project: MockProject, pluginRelativePath: String) {
        val pluginDescriptor = getOrCalculatePluginDescriptor(PluginDesignation(pluginRelativePath, project))
        val listenerElements = pluginDescriptor.projectElementsContainer.listeners.ifEmpty { return }

        val listenersMap = ConcurrentHashMap<String, MutableList<PluginListenerDescriptor>>()
        for (listenerElement in listenerElements) {
            val descriptor = ListenerDescriptor(
                null,
                listenerElement.listenerClassName,
                listenerElement.topicClassName,
                listenerElement.activeInTestMode,
                listenerElement.activeInHeadlessMode,
            )
            val pluginListener = PluginListenerDescriptor(descriptor, fakePluginDescriptor)
            listenersMap.computeIfAbsent(listenerElement.topicClassName) { mutableListOf() }.add(pluginListener)
        }
        (project.analysisMessageBus as MessageBusEx).setLazyListeners(listenersMap)
    }

    private inline fun registerExtensionPoints(
        componentManager: MockComponentManager,
        pluginRelativePath: String,
        container: RawPluginDescriptor.() -> ScopedElementsContainer,
    ) {
        val pluginDescriptor = getOrCalculatePluginDescriptor(PluginDesignation(pluginRelativePath, componentManager))
        for (ep in pluginDescriptor.container().extensionPoints) {
            val epName = ep.qualifiedName ?: ep.name ?: continue
            if (epName in forbiddenExtensionPointNames) continue
            val className = ep.`interface` ?: ep.beanClass ?: continue
            CoreApplicationEnvironment.registerExtensionPoint(
                componentManager.extensionArea,
                epName,
                componentManager.loadClass<Any>(className, fakePluginDescriptor),
            )
        }
    }

    private inline fun registerServices(
        componentManager: MockComponentManager,
        pluginRelativePath: String,
        container: RawPluginDescriptor.() -> ScopedElementsContainer,
    ) {
        val pluginDescriptor = getOrCalculatePluginDescriptor(PluginDesignation(pluginRelativePath, componentManager))
        for (serviceDescriptor in pluginDescriptor.container().services) {
            val implClass = componentManager.loadClass<Any>(serviceDescriptor.serviceImplementation ?: continue, fakePluginDescriptor)
            val serviceInterface = serviceDescriptor.serviceInterface
            if (serviceInterface != null) {
                val ifaceClass = componentManager.loadClass<Any>(serviceInterface, fakePluginDescriptor)
                @Suppress("UNCHECKED_CAST")
                componentManager.registerServiceWithInterface(ifaceClass as Class<Any>, implClass)
            } else {
                componentManager.registerService(implClass)
            }
        }
    }

    private fun registerExtensionPointImplementations(
        componentManager: MockComponentManager,
        pluginRelativePath: String,
    ) {
        val pluginDescriptor = getOrCalculatePluginDescriptor(PluginDesignation(pluginRelativePath, componentManager))
        val extensions = pluginDescriptor.extensions
        for (epName in allowedExtensionPointNames) {
            val point = componentManager.extensionArea.getExtensionPointIfRegistered<Any>(epName) ?: continue
            val descriptors = extensions[epName] ?: continue
            for (descriptor in descriptors) {
                val implClass = componentManager.loadClass<Any>(descriptor.implementation ?: continue, fakePluginDescriptor)
                @Suppress("UNCHECKED_CAST")
                point.registerExtension(implClass.getDeclaredConstructor().newInstance() as Any)
            }
        }
    }

    private val forbiddenExtensionPointNames = setOf(
        "org.jetbrains.kotlin.defaultErrorMessages",
    )

    private val allowedExtensionPointNames = listOf(
        "org.jetbrains.kotlin.analysis.additionalKDocResolutionProvider",
        "org.jetbrains.kotlin.kaAdditionalKDocResolutionProvider",
        "org.jetbrains.kotlin.kotlinContentScopeRefiner",
        "org.jetbrains.kotlin.kotlinGlobalSearchScopeMergeStrategy",
        "org.jetbrains.kotlin.psiReferenceProvider",
        "com.intellij.psi.classFileDecompiler",
    )

    private val MockComponentManager.classLoader: ClassLoader
        get() = loadClass<Any>(PluginDesignation::class.java.name, fakePluginDescriptor).classLoader

    @Suppress("UNCHECKED_CAST")
    private fun <T> MockComponentManager.registerServiceWithInterface(ifaceClass: Class<T>, implClass: Class<T>) {
        registerService(ifaceClass, implClass)
    }
}
