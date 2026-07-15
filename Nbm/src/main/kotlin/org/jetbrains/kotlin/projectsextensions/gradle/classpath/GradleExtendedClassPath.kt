/** *****************************************************************************
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
package org.jetbrains.kotlin.projectsextensions.gradle.classpath

import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.projectsextensions.ClassPathExtender
import org.netbeans.api.java.classpath.ClassPath
import org.netbeans.api.java.project.JavaProjectConstants
import org.netbeans.api.project.Project
import org.netbeans.api.project.ProjectUtils
import org.netbeans.api.project.Sources
import org.netbeans.spi.java.classpath.ClassPathProvider
import org.netbeans.spi.java.classpath.support.ClassPathSupport

/**
 * Resolves classpaths for a Gradle-backed [Project] via the [ClassPathProvider] found in the
 * project's [Project.getLookup]. Two Gradle NetBeans integrations are supported:
 * - the old third-party "NetBeans Gradle Support" plugin, whose provider exposes a whole-project,
 *   file-less `getClassPaths(String)` accessor (looked up reflectively, since that method isn't
 *   part of the standard [ClassPathProvider] interface);
 * - Apache NetBeans's built-in Gradle module, whose provider implements only the standard
 *   [ClassPathProvider.findClassPath] (file, type), which requires a representative [FileObject]
 *   per classpath type — obtained from the project's source roots and aggregated.
 *
 * @author baratynskiy
 */
class GradleExtendedClassPath(val project: Project) : ClassPathExtender {

    private val classPathProvider: ClassPathProvider? = project.lookup.lookup(ClassPathProvider::class.java)

    override fun getProjectSourcesClassPath(type: String): ClassPath =
            if (classPathProvider == null) ClassPath.EMPTY else getClassPath(type)

    private fun getClassPath(type: String): ClassPath {
        try {
            val method = classPathProvider?.javaClass?.getMethod("getClassPaths", String::class.java)
            if (method != null) {
                return method.invoke(classPathProvider, type) as ClassPath
            }
        } catch (ex: ReflectiveOperationException) {
            KotlinLogger.INSTANCE.logWarning(ex.message)
        }
        return getClassPathViaStandardProvider(type)
    }

    /**
     * Fallback for [ClassPathProvider]s that only implement the standard SPI method, which
     * requires a file rather than a bare type string. Queries every project source-root file
     * and merges the per-root results, mirroring how [KotlinProjectHelper] aggregates
     * boot/compile/source into one proxy classpath.
     *
     * Apache NetBeans's built-in Gradle module (`GradleSourcesImpl`) only registers source
     * groups under the `"java"` and `"kotlin"` types (plus `"resources"`/`"generated"`), never
     * [Sources.TYPE_GENERIC] — so all of them are queried and the results merged.
     */
    private fun getClassPathViaStandardProvider(type: String): ClassPath {
        val provider = classPathProvider ?: return ClassPath.EMPTY
        val sources = ProjectUtils.getSources(project)
        val roots = listOf(Sources.TYPE_GENERIC, JavaProjectConstants.SOURCES_TYPE_JAVA, "kotlin")
            .flatMap { sources.getSourceGroups(it).toList() }
            .map { it.rootFolder }
            .distinct()
        val classPaths = roots.mapNotNull { provider.findClassPath(it, type) }.distinct()
        return when (classPaths.size) {
            0 -> ClassPath.EMPTY
            1 -> classPaths[0]
            else -> ClassPathSupport.createProxyClassPath(*classPaths.toTypedArray())
        }
    }

}