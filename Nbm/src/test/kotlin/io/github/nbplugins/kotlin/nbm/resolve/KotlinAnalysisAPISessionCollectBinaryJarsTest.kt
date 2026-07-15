/*******************************************************************************
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
package io.github.nbplugins.kotlin.nbm.resolve

import org.netbeans.api.java.classpath.ClassPath
import org.netbeans.api.project.Project
import org.netbeans.junit.NbTestCase
import org.netbeans.spi.java.classpath.ClassPathProvider
import org.netbeans.spi.java.classpath.support.ClassPathSupport
import org.openide.filesystems.FileObject
import org.openide.filesystems.FileUtil
import org.openide.util.lookup.Lookups
import java.nio.file.Files
import java.util.zip.ZipOutputStream
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Regression test for [KotlinAnalysisAPISession]'s private `collectBinaryJars`: it must
 * include directory-based classpath entries, not just `.jar` files.
 *
 * Gradle's IDE-mode compile classpath represents project-to-project (subproject) dependencies
 * as a directory of compiled `.class` files (e.g. `otherModule/build/classes/kotlin/main/`)
 * rather than a JAR. A filter that only kept `.jar` entries silently dropped every
 * sibling-Gradle-subproject dependency from the K2 session, breaking navigation/hover/
 * completion for symbols declared in another subproject.
 */
class KotlinAnalysisAPISessionCollectBinaryJarsTest : NbTestCase("KotlinAnalysisAPISessionCollectBinaryJarsTest") {

    private class StandardClassPathProvider(private val classPath: ClassPath) : ClassPathProvider {
        override fun findClassPath(file: FileObject?, type: String?): ClassPath = classPath
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectBinaryJars(
        project: Project,
        sourceRoots: List<java.nio.file.Path> = emptyList()
    ): List<java.nio.file.Path> {
        val companion = KotlinAnalysisAPISession::class.companionObject!!
        val fn = companion.declaredFunctions.first { it.name == "collectBinaryJars" }
        fn.isAccessible = true
        val instance = KotlinAnalysisAPISession::class.companionObjectInstance
        return fn.call(instance, project, sourceRoots) as List<java.nio.file.Path>
    }

    fun testCollectBinaryJars_includesDirectoryClasspathEntries() {
        val projectBaseDir = Files.createTempDirectory("kaas_proj").toFile()
        val classesDir = Files.createTempDirectory("kaas_classes").toFile()
        val jarFile = Files.createTempFile("kaas_lib", ".jar").toFile()
        ZipOutputStream(jarFile.outputStream()).close() // valid (empty) zip archive
        try {
            val projectDirFo = FileUtil.toFileObject(projectBaseDir)!!
            val classesDirFo = FileUtil.toFileObject(classesDir)!!
            val jarFo = FileUtil.toFileObject(jarFile)!!
            val jarRootFo = FileUtil.getArchiveRoot(jarFo)!!

            val cp = ClassPathSupport.createClassPath(classesDirFo, jarRootFo)
            val provider = StandardClassPathProvider(cp)
            val project: Project =
                org.netbeans.modules.gradle.NbGradleProjectImpl(projectDirFo, Lookups.fixed(provider))

            // No overlapping source roots here — isolates the "include directories" behavior
            // from the self-referential-exclusion behavior covered by the test below.
            val result = collectBinaryJars(project, sourceRoots = emptyList())

            assertTrue(
                "must include the directory classpath entry (simulating a Gradle subproject dependency)",
                result.any { it.toFile().absolutePath == classesDir.absolutePath }
            )
            assertTrue(
                "must still include .jar classpath entries",
                result.any { it.toString().endsWith(".jar") }
            )
        } finally {
            projectBaseDir.deleteRecursively()
            classesDir.deleteRecursively()
            jarFile.delete()
        }
    }

    /**
     * Regression test: some project types put their own compiled-output directory on the
     * COMPILE classpath. If that directory were kept as a binary root, it would duplicate the
     * project's own source files as a separate K2 library module, causing module-resolution
     * errors ([org.jetbrains.kotlin.analysis.api.KaBaseIllegalPsiException] "cannot be analyzed
     * in the context of the current session"). Such directories must be excluded whenever they
     * coincide with (or are nested within) one of the project's own source roots.
     */
    fun testCollectBinaryJars_excludesDirectoriesThatOverlapSourceRoots() {
        val projectBaseDir = Files.createTempDirectory("kaas_proj_self").toFile()
        val selfOutputDir = projectBaseDir.resolve("build/classes/kotlin/main").apply { mkdirs() }
        try {
            val projectDirFo = FileUtil.toFileObject(projectBaseDir)!!
            val selfOutputDirFo = FileUtil.toFileObject(selfOutputDir)!!

            val cp = ClassPathSupport.createClassPath(selfOutputDirFo)
            val provider = StandardClassPathProvider(cp)
            val project: Project =
                org.netbeans.modules.gradle.NbGradleProjectImpl(projectDirFo, Lookups.fixed(provider))

            val result = collectBinaryJars(project, sourceRoots = listOf(selfOutputDir.toPath()))

            assertTrue(
                "a binary directory entry that coincides with one of the project's own source roots must be excluded",
                result.none { it.toFile().absolutePath == selfOutputDir.absolutePath }
            )
        } finally {
            projectBaseDir.deleteRecursively()
        }
    }

    /**
     * Regression test: the self-exclusion check must be scoped to the *current project's own*
     * directory. If [sourceRoots] contains a broad entry (e.g. one at or above the whole
     * multi-module reactor root — which can happen if the project's SOURCE classpath is
     * reported more broadly than expected), a sibling Gradle subproject's own binary output
     * directory must NOT be mistaken for a self-referential entry and dropped — that was the
     * regression that caused `ktor-boot-observability-core`'s directory to disappear even
     * though it was correctly present in the raw classpath.
     */
    fun testCollectBinaryJars_doesNotExcludeSiblingModuleDirectories_evenWithBroadSourceRoot() {
        val reactorRoot = Files.createTempDirectory("kaas_reactor").toFile()
        val projectBaseDir = reactorRoot.resolve("kafka-module").apply { mkdirs() }
        val siblingModuleOutputDir = reactorRoot.resolve("observability-core-module/build/classes/kotlin/main")
            .apply { mkdirs() }
        try {
            val projectDirFo = FileUtil.toFileObject(projectBaseDir)!!
            val siblingOutputDirFo = FileUtil.toFileObject(siblingModuleOutputDir)!!

            val cp = ClassPathSupport.createClassPath(siblingOutputDirFo)
            val provider = StandardClassPathProvider(cp)
            val project: Project =
                org.netbeans.modules.gradle.NbGradleProjectImpl(projectDirFo, Lookups.fixed(provider))

            // A broad source root at the whole reactor root — would `startsWith`-match every
            // sibling module's directory if the scoping-to-own-project-directory guard were absent.
            val result = collectBinaryJars(project, sourceRoots = listOf(reactorRoot.toPath()))

            assertTrue(
                "a sibling module's own binary directory must survive even when sourceRoots contains a broad ancestor entry",
                result.any { it.toFile().absolutePath == siblingModuleOutputDir.absolutePath }
            )
        } finally {
            reactorRoot.deleteRecursively()
        }
    }

    /**
     * Regression test: for a Kotlin-only Gradle subproject dependency, the compile classpath
     * only ever reports `<module>/build/classes/java/<sourceSet>/` — NetBeans's Gradle support
     * is Java-plugin-oriented and doesn't know about the Kotlin plugin's own output directory,
     * `<module>/build/classes/kotlin/<sourceSet>/`, where the module's actual `.class` files
     * live. The Kotlin sibling directory must be added too, whenever it exists on disk, or every
     * symbol from a Kotlin-only sibling subproject is silently unresolvable.
     */
    fun testCollectBinaryJars_addsKotlinClassesSiblingOfJavaClassesDirectory() {
        val projectBaseDir = Files.createTempDirectory("kaas_proj_kt").toFile()
        val moduleBuildDir = Files.createTempDirectory("kaas_module_build").toFile()
        val javaClassesDir = moduleBuildDir.resolve("classes/java/main").apply { mkdirs() }
        val kotlinClassesDir = moduleBuildDir.resolve("classes/kotlin/main").apply { mkdirs() }
        try {
            val projectDirFo = FileUtil.toFileObject(projectBaseDir)!!
            val javaClassesDirFo = FileUtil.toFileObject(javaClassesDir)!!

            val cp = ClassPathSupport.createClassPath(javaClassesDirFo)
            val provider = StandardClassPathProvider(cp)
            val project: Project =
                org.netbeans.modules.gradle.NbGradleProjectImpl(projectDirFo, Lookups.fixed(provider))

            val result = collectBinaryJars(project, sourceRoots = emptyList())

            assertTrue(
                "must still include the (empty) java/main classpath entry as reported",
                result.any { it.toFile().absolutePath == javaClassesDir.absolutePath }
            )
            assertTrue(
                "must additionally include the sibling kotlin/main directory where the module's actual .class files live",
                result.any { it.toFile().absolutePath == kotlinClassesDir.absolutePath }
            )
        } finally {
            projectBaseDir.deleteRecursively()
            moduleBuildDir.deleteRecursively()
        }
    }

    /**
     * Regression test: in the real bug, `<module>/build/classes/java/<sourceSet>/` didn't just
     * happen to be *empty* — it didn't exist **at all** (a genuinely Kotlin-only module never
     * runs `compileJava`, so Gradle never creates that directory). An earlier version of this
     * fix computed the Kotlin sibling only for entries that survived the existence/type filter,
     * so a non-existent java classes directory was dropped before its Kotlin sibling was ever
     * considered — silently losing the sibling too. The Kotlin sibling must be added even when
     * the java classes directory it's derived from doesn't exist on disk.
     */
    fun testCollectBinaryJars_addsKotlinSibling_evenWhenJavaClassesDirDoesNotExist() {
        val projectBaseDir = Files.createTempDirectory("kaas_proj_kt_missing").toFile()
        val moduleBuildDir = Files.createTempDirectory("kaas_module_build_missing").toFile()
        val javaClassesDir = moduleBuildDir.resolve("classes/java/main") // deliberately NOT created
        val kotlinClassesDir = moduleBuildDir.resolve("classes/kotlin/main").apply { mkdirs() }
        try {
            val projectDirFo = FileUtil.toFileObject(projectBaseDir)!!

            // Built from a plain path string, like the real NetBeans Gradle classpath reports a
            // non-existent directory: ClassPathSupport.createClassPath(FileObject...) requires
            // the FileObject to exist, which the real bug scenario doesn't need.
            val cp = ClassPathSupport.createClassPath(javaClassesDir.absolutePath)
            val provider = StandardClassPathProvider(cp)
            val project: Project =
                org.netbeans.modules.gradle.NbGradleProjectImpl(projectDirFo, Lookups.fixed(provider))

            val result = collectBinaryJars(project, sourceRoots = emptyList())

            assertTrue(
                "must include the sibling kotlin/main directory even though build/classes/java/main never existed",
                result.any { it.toFile().absolutePath == kotlinClassesDir.absolutePath }
            )
        } finally {
            projectBaseDir.deleteRecursively()
            moduleBuildDir.deleteRecursively()
        }
    }
}
