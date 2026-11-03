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
import io.github.nbplugins.kotlin.refactoring.KotlinConstructorSignatureCheck
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Integration tests for the constructor-signature comparison used by generic file Rename. */
class KotlinConstructorSignatureCheckTest : KotlinTestCase("KotlinConstructorSignatureCheckTest", "moveFile") {

    /**
     * Verifies a fully qualified parameter type is recognized as the constructor's type.
     *
     * `kotlin.Int` and `Int` denote the same type, so the function would collide with the renamed
     * constructor even though the source spellings differ.
     */
    fun testConflictsWithPrimaryConstructor_reportsEquivalentQualifiedType() {
        withSession(
            name = "qualified",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Types.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun Bar(n: kotlin.Int) = n\n")
            },
        ) { session, root ->
            val types = session.ktFile(root.resolve("source/Types.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))

            assertTrue(
                "Int and kotlin.Int are the same parameter type",
                KotlinConstructorSignatureCheck.conflictsWithPrimaryConstructor(
                    primaryClass(types),
                    functions.declarations.filterIsInstance<KtNamedFunction>().single(),
                ),
            )
        }
    }

    /** Verifies a function with a different parameter type does not conflict. */
    fun testConflictsWithPrimaryConstructor_ignoresDifferentType() {
        withSession(
            name = "different",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Types.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun Bar(n: String) = n\n")
            },
        ) { session, root ->
            val types = session.ktFile(root.resolve("source/Types.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))

            assertFalse(
                "String cannot collide with an Int parameter",
                KotlinConstructorSignatureCheck.conflictsWithPrimaryConstructor(
                    primaryClass(types),
                    functions.declarations.filterIsInstance<KtNamedFunction>().single(),
                ),
            )
        }
    }

    /** Verifies an extension function is not reported despite matching parameter types. */
    fun testConflictsWithPrimaryConstructor_ignoresExtensionFunction() {
        withSession(
            name = "extension",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Types.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun Foo.Bar(n: Int) = n\n")
            },
        ) { session, root ->
            val types = session.ktFile(root.resolve("source/Types.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))

            assertFalse(
                "an extension function has an implicit receiver",
                KotlinConstructorSignatureCheck.conflictsWithPrimaryConstructor(
                    primaryClass(types),
                    functions.declarations.filterIsInstance<KtNamedFunction>().single(),
                ),
            )
        }
    }

    /** @return the single top-level class of [file]. */
    private fun primaryClass(file: KtFile): KtClass = file.declarations.filterIsInstance<KtClass>().single()

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
            println("kotlin-stdlib not on test classpath — skipping constructor signature integration test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-constructor-signature-$name")
        try {
            prepare(root)
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "constructor-signature-$name-integration",
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
