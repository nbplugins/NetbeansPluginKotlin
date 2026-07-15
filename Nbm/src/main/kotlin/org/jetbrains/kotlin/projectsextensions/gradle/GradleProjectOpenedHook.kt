/** *****************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.kotlin.projectsextensions.gradle

import io.github.nbplugins.kotlin.nbm.formatting.options.ProjectCodeStyleStorage
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper.doInitialScan
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper.updateExtendedClassPath
import org.jetbrains.kotlin.log.KotlinLogger
import org.netbeans.api.progress.ProgressHandleFactory
import org.netbeans.api.project.Project
import org.netbeans.spi.project.ui.ProjectOpenedHook
import java.beans.PropertyChangeListener
import kotlin.concurrent.thread

/**
 *
 * @author baratynskiy
 */
class GradleProjectOpenedHook(private val project: Project) : ProjectOpenedHook() {

    override fun projectOpened() {
        ProjectCodeStyleStorage.onProjectOpened(project)
        thread {
            KotlinProjectHelper.postTask(Runnable {
                val progressBar = ProgressHandleFactory.createHandle("Loading Kotlin environment")
                progressBar.start()
                KotlinAnalysisAPISession.getSession(project)
                progressBar.finish()
            })

            project.doInitialScan()
            addGradleProjectInfoListener(project)
        }
    }

    override fun projectClosed() {
        ProjectCodeStyleStorage.onProjectClosed(project)
    }

}

/**
 * Registers a listener for Apache NetBeans's built-in Gradle module's `PROP_PROJECT_INFO`
 * change (fired via `org.netbeans.modules.gradle.api.NbGradleProject`, accessed reflectively
 * since [org.jetbrains.kotlin.projectsextensions.gradle] has no compile-time dependency on the
 * Gradle module — same "support both" reasoning as [org.jetbrains.kotlin.projectsextensions.gradle.classpath.GradleExtendedClassPath]).
 *
 * The built-in Gradle module resolves a project's classpath asynchronously: when
 * [GradleProjectOpenedHook.projectOpened] eagerly pre-warms the K2 session, Gradle's own project
 * model (and therefore the classpath the Kotlin plugin reads via [ClassPathProvider]) may not be
 * loaded yet, so the very first session gets cached with an empty classpath. `PROP_PROJECT_INFO`
 * fires once that model finishes (re)loading, so re-resolving the classpath then and invalidating
 * the cached K2 session (via [updateExtendedClassPath]) picks up the real dependencies.
 *
 * No-op (and silent) when the built-in Gradle module isn't present — e.g. only the old
 * third-party "NetBeans Gradle Support" plugin is installed, which has no such API and instead
 * exposes its classpath synchronously via `getClassPaths(String)`.
 *
 * @param project the opened Gradle project
 */
private fun addGradleProjectInfoListener(project: Project) {
    try {
        val nbGradleProjectClass = Class.forName("org.netbeans.modules.gradle.api.NbGradleProject")
        val propProjectInfo = nbGradleProjectClass.getField("PROP_PROJECT_INFO").get(null) as String
        val listener = PropertyChangeListener { event ->
            if (event.propertyName == propProjectInfo) {
                KotlinLogger.INSTANCE.logInfo(
                    "GradleProjectOpenedHook: PROP_PROJECT_INFO fired for ${project.projectDirectory.path}, refreshing classpath"
                )
                project.updateExtendedClassPath()
            }
        }
        nbGradleProjectClass.getMethod(
            "addPropertyChangeListener", Project::class.java, PropertyChangeListener::class.java
        ).invoke(null, project, listener)
    } catch (ex: ClassNotFoundException) {
        // Built-in Gradle module not present (e.g. old third-party plugin only) — nothing to do.
    } catch (ex: ReflectiveOperationException) {
        KotlinLogger.INSTANCE.logWarning("Cannot register Gradle PROP_PROJECT_INFO listener: ${ex.message}")
    }
}