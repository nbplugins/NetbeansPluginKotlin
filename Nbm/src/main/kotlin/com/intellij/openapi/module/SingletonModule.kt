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
package com.intellij.openapi.module

import com.intellij.diagnostic.ActivityCategory
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Key
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.messages.MessageBus
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime-precedence twin of `KotlinRefactoring`'s `com.intellij.openapi.module.SingletonModule` —
 * duplicated here (same fully-qualified name) so `Nbm`'s own classloader-first-loaded classes
 * shadow whatever real `Module`/`ModuleUtilCore`/`ModuleType` implementation a bundled platform JAR
 * provides (see `docs/stubs.md`'s "Conflict resolution rule"). `KotlinRefactoring` still compiles
 * against its own copy (it does not depend on `Nbm`); at actual plugin runtime, this copy wins.
 *
 * This plugin has no concept of multiple Kotlin modules within one NetBeans project — the
 * `StandaloneAnalysisAPISession` registers exactly one source `KaModule` per project, and every
 * element in the project resolves to the same [SingletonModule] instance, which is the true state
 * of affairs for every project type this plugin supports (Maven/Gradle/Ant, always single-module).
 */
class SingletonModule(private val project: Project) : Module {
    override fun getMessageBus(): MessageBus = project.messageBus
    override fun getModuleFile() = null
    override fun getModuleNioFile(): Path = Path.of(project.basePath ?: ".")
    override fun getProject(): Project = project
    override fun getName(): String = project.name
    override fun isDisposed(): Boolean = project.isDisposed
    override fun isLoaded(): Boolean = true
    override fun setOption(key: String, value: String?) {}
    override fun getOptionValue(key: String): String? = null
    override fun getModuleScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleScope(includeTests: Boolean): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleWithLibrariesScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleWithDependenciesScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleContentScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleContentWithDependenciesScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleWithDependenciesAndLibrariesScope(includeTests: Boolean): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleWithDependentsScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleTestsWithDependentsScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun getModuleRuntimeScope(includeTests: Boolean): GlobalSearchScope = GlobalSearchScope.allScope(project)
    override fun dispose() {}

    // ComponentManager (Module's supertype): this plugin's standalone container never registers
    // components/services on a per-Module basis (only on the project/application level, handled
    // by KotlinAnalysisAPISession), so these are legitimately empty/absent, not faked.
    private val userData = ConcurrentHashMap<Key<*>, Any?>()
    override fun <T : Any> getComponent(clazz: Class<T>): T? = null
    override fun hasComponent(clazz: Class<*>): Boolean = false
    override fun <T : Any> getService(clazz: Class<T>): T? = null
    override fun <T : Any> instantiateClass(clazz: Class<T>, pluginId: PluginId): T =
        throw UnsupportedOperationException("SingletonModule does not instantiate plugin classes")
    override fun <T : Any> instantiateClass(className: String, pluginDescriptor: PluginDescriptor): T =
        throw UnsupportedOperationException("SingletonModule does not instantiate plugin classes")
    override fun <T : Any> instantiateClassWithConstructorInjection(clazz: Class<T>, key: Any, pluginId: PluginId): T =
        throw UnsupportedOperationException("SingletonModule does not instantiate plugin classes")
    override fun <T : Any> loadClass(className: String, pluginDescriptor: PluginDescriptor): Class<T> =
        throw ClassNotFoundException(className)
    override fun createError(message: String, pluginId: PluginId): RuntimeException = RuntimeException(message)
    override fun createError(error: Throwable, pluginId: PluginId): RuntimeException = RuntimeException(error)
    override fun createError(
        message: String,
        error: Throwable?,
        pluginId: PluginId,
        attachments: MutableMap<String, String>?
    ): RuntimeException = RuntimeException(message, error)
    override fun getActivityCategory(isExtension: Boolean): ActivityCategory =
        if (isExtension) ActivityCategory.MODULE_EXTENSION else ActivityCategory.MODULE_SERVICE
    override fun isInjectionForExtensionSupported(): Boolean = false
    override fun getExtensionArea(): ExtensionsArea =
        throw UnsupportedOperationException("SingletonModule has no extension area")
    override fun getDisposed(): Condition<*> = Condition<Any?> { isDisposed }
    @Suppress("UNCHECKED_CAST")
    override fun <T> getUserData(key: Key<T>): T? = userData[key] as T?
    override fun <T> putUserData(key: Key<T>, value: T?) {
        if (value == null) userData.remove(key) else userData[key] = value
    }

    companion object {
        private val instances = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Project, SingletonModule>())

        /** Returns the one [SingletonModule] instance for [project], creating it on first access. */
        fun forProject(project: Project): SingletonModule = instances.getOrPut(project) { SingletonModule(project) }
    }
}
