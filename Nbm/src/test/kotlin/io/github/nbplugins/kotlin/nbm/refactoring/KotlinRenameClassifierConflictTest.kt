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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Integration tests for the class-name conflict lookup used by generic file Rename. */
class KotlinRenameClassifierConflictTest : KotlinTestCase("KotlinRenameClassifierConflictTest", "moveFile") {

    /** Verifies a class already declared in the same package blocks the rename. */
    fun testFindClassifierConflict_reportsClassInSamePackage() {
        withSession(
            name = "class",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo\n")
                Files.writeString(root.resolve("source/Types.kt"), "package p\n\nclass Bar\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val types = session.ktFile(root.resolve("source/Types.kt"))

            val conflict = findClassifierConflict(source, primaryClass(source), "Bar", listOf(source, types))

            assertEquals("Bar", conflict?.name)
        }
    }

    /** Verifies a class in another package does not block the rename. */
    fun testFindClassifierConflict_ignoresOtherPackages() {
        withSession(
            name = "other-package",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("other"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo\n")
                Files.writeString(root.resolve("other/Types.kt"), "package q\n\nclass Bar\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val other = session.ktFile(root.resolve("other/Types.kt"))

            val conflict = findClassifierConflict(source, primaryClass(source), "Bar", listOf(source, other))

            assertNull("another package cannot collide with the renamed class", conflict)
        }
    }

    /** Verifies the renamed class itself is not reported when the name is unchanged. */
    fun testFindClassifierConflict_ignoresTheRenamedClass() {
        withSession(
            name = "self",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))

            val conflict = findClassifierConflict(source, primaryClass(source), "Foo", listOf(source))

            assertNull("the declaration being renamed must not conflict with itself", conflict)
        }
    }

    /** Verifies a type alias with the target name also blocks the rename. */
    fun testFindClassifierConflict_reportsTypeAliasInSamePackage() {
        withSession(
            name = "typealias",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo\n")
                Files.writeString(root.resolve("source/Aliases.kt"), "package p\n\ntypealias Bar = Int\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val aliases = session.ktFile(root.resolve("source/Aliases.kt"))

            val conflict = findClassifierConflict(source, primaryClass(source), "Bar", listOf(source, aliases))

            assertEquals("Bar", conflict?.name)
        }
    }

    /** @return the single top-level class of [source]. */
    private fun primaryClass(source: KtFile): KtClass = source.declarations.filterIsInstance<KtClass>().single()

    /** @return the session source for [path], failing the test when it is absent. */
    private fun KotlinAnalysisAPISession.ktFile(path: Path): KtFile =
        getKtFileForPath(path.toString()) ?: error("No Kotlin source PSI for $path")

    /**
     * Writes files through [prepare], then runs [check] against a session created afterwards.
     *
     * The session snapshots its source roots at creation, so files must exist before it is built.
     */
    private fun withSession(
        name: String,
        prepare: (Path) -> Unit,
        check: (KotlinAnalysisAPISession, Path) -> Unit,
    ) {
        val stdlib = findStdlibJar() ?: run {
            println("kotlin-stdlib not on test classpath — skipping rename conflict integration test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-rename-conflict-$name")
        try {
            prepare(root)
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "rename-conflict-$name-integration",
                binaryJars = listOf(stdlib),
                sourceRoots = listOf(root),
            )
            check(session, root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Locates Kotlin stdlib supplied by the Maven test runtime. */
    private fun findStdlibJar(): Path? = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .map(Path::of)
        .firstOrNull { it.fileName?.toString()?.startsWith("kotlin-stdlib") == true && it.toFile().exists() }
}
