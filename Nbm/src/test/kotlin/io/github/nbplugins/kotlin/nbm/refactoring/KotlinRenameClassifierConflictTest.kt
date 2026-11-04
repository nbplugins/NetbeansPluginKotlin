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

            val conflict = findRenameConflict(source, primaryClass(source), "Bar", listOf(source, types))

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

            val conflict = findRenameConflict(source, primaryClass(source), "Bar", listOf(source, other))

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

            val conflict = findRenameConflict(source, primaryClass(source), "Foo", listOf(source))

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

            val conflict = findRenameConflict(source, primaryClass(source), "Bar", listOf(source, aliases))

            assertEquals("Bar", conflict?.name)
        }
    }

    /**
     * Verifies a top-level function callable the same way as the new constructor blocks the rename.
     *
     * A zero-argument `fun Bar()` and a renamed `class Before` becoming `class Bar` cannot coexist
     * in one package, since both are called as `Bar()`.
     */
    fun testFindRenameConflict_reportsSameSignatureFunction() {
        withSession(
            name = "function",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun Bar() = 1\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))

            val conflict = findRenameConflict(source, primaryClass(source), "Bar", listOf(source, functions))

            assertEquals("Bar", conflict?.name)
        }
    }

    /** Verifies a function with a different signature does not conflict with the constructor. */
    fun testFindRenameConflict_ignoresFunctionWithDifferentSignature() {
        withSession(
            name = "function-signature",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun Bar(n: Int) = n\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))

            val conflict = findRenameConflict(source, primaryClass(source), "Bar", listOf(source, functions))

            assertNull("a function taking parameters cannot collide with a no-arg constructor", conflict)
        }
    }

    /**
     * Verifies a caller whose existing binding would be shadowed by the rewritten import blocks
     * the rename.
     *
     * The caller imports `p.Foo` and already calls `q.Bar` from its own package. After the rename
     * its import becomes `p.Bar`, which takes precedence over `q.Bar` without rewriting those
     * call sites.
     */
    fun testFindCallerScopeConflict_reportsReboundSamePackageClass() {
        withSession(
            name = "caller-scope",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("other"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("other/Types.kt"), "package q\n\nclass Bar\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package q\n\nimport p.Foo\n\nfun use() = Foo(1) + Bar()\n",
                )
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val types = session.ktFile(root.resolve("other/Types.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findCallerScopeConflict(
                source,
                primaryClass(source),
                "Bar",
                listOf(source, types, usage),
            )

            assertEquals("Usage.kt", conflict?.virtualFile?.name)
        }
    }

    /** Verifies a caller that never mentions the new name is not reported. */
    fun testFindCallerScopeConflict_ignoresCallerWithoutNewName() {
        withSession(
            name = "caller-scope-clean",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("usage/Usage.kt"), "package q\n\nimport p.Foo\n\nfun use() = Foo(1)\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findCallerScopeConflict(source, primaryClass(source), "Bar", listOf(source, usage))

            assertNull("only a caller that already names Bar can be rebound", conflict)
        }
    }

    /**
     * Verifies a same-package caller using the new name blocks the rename.
     *
     * `fun After(n: Number)` already exists in the package, and the renamed `Before(Int)` becomes
     * an `After(Int)` constructor. The caller needs no import for either, but `After(1)` would
     * start selecting the more specific constructor while still compiling.
     */
    fun testFindSamePackageOverloadConflict_reportsCallerUsingNewName() {
        withSession(
            name = "same-package-overload",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun After(n: Number) = \"other\"\n")
                Files.writeString(root.resolve("usage/Usage.kt"), "package p\n\nfun use() = After(1).toString()\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findSamePackageOverloadConflict(
                source,
                primaryClass(source),
                "After",
                listOf(source, functions, usage),
            )

            assertEquals("Usage.kt", conflict?.virtualFile?.name)
        }
    }

    /** Verifies a same-package caller of an unrelated name is not reported. */
    fun testFindSamePackageOverloadConflict_ignoresCallerWithoutNewName() {
        withSession(
            name = "same-package-overload-clean",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("usage/Usage.kt"), "package p\n\nfun use() = Foo(1)\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findSamePackageOverloadConflict(
                source,
                primaryClass(source),
                "After",
                listOf(source, usage),
            )

            assertNull("a caller that never names After cannot switch overload", conflict)
        }
    }

    /**
     * Verifies a star-imported `After` of another package blocks the rename.
     *
     * A same-package declaration outranks a star import, so the caller's unchanged `After()`
     * would start constructing the renamed `p.After` instead of `lib.After`.
     */
    fun testFindSamePackageOverloadConflict_reportsStarImportedNewName() {
        withSession(
            name = "same-package-star-import",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.createDirectories(root.resolve("lib"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("lib/Library.kt"), "package lib\n\nclass After\n")
                Files.writeString(root.resolve("usage/Usage.kt"), "package p\n\nimport lib.*\n\nfun use() = After()\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val library = session.ktFile(root.resolve("lib/Library.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findSamePackageOverloadConflict(
                source,
                primaryClass(source),
                "After",
                listOf(source, library, usage),
            )

            assertEquals("Usage.kt", conflict?.virtualFile?.name)
        }
    }

    /**
     * Verifies an explicitly imported `After` of another package is left alone.
     *
     * An explicit import outranks a same-package declaration, so the caller keeps constructing
     * `lib.After` after the rename and no conflict is reported.
     */
    fun testFindSamePackageOverloadConflict_ignoresExplicitlyImportedNewName() {
        withSession(
            name = "same-package-explicit-import",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.createDirectories(root.resolve("lib"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("lib/Library.kt"), "package lib\n\nclass After\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package p\n\nimport lib.After\n\nfun use() = After()\n",
                )
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val library = session.ktFile(root.resolve("lib/Library.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findSamePackageOverloadConflict(
                source,
                primaryClass(source),
                "After",
                listOf(source, library, usage),
            )

            assertNull("an explicit import keeps winning over the package", conflict)
        }
    }

    /**
     * Verifies a caller of another package that imports the new name blocks the rename.
     *
     * `import p.After` binds the whole overload set of `p.After`, so after `Before(Int)` becomes
     * an `After(Int)` constructor the caller's untouched `After(1)` selects the constructor
     * instead of the `fun After(n: Number)` it currently calls.
     */
    fun testFindSamePackageOverloadConflict_reportsExplicitlyImportedNewName() {
        withSession(
            name = "external-explicit-import",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun After(n: Number) = \"other\"\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package q\n\nimport p.After\n\nfun use() = After(1).toString()\n",
                )
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findSamePackageOverloadConflict(
                source,
                primaryClass(source),
                "After",
                listOf(source, functions, usage),
            )

            assertEquals("Usage.kt", conflict?.virtualFile?.name)
        }
    }

    /**
     * Verifies a caller that imports the new name under an alias blocks the rename.
     *
     * `import p.After as make` binds the whole overload set of `p.After` under `make`, so the
     * caller's untouched `make(1)` selects the newly named `After(Int)` constructor instead of the
     * `fun After(n: Number)` it currently calls. A reference spelling only the alias must therefore
     * count as a reference to the destination name.
     */
    fun testFindSamePackageOverloadConflict_reportsAliasedImportedNewName() {
        withSession(
            name = "external-aliased-import",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun After(n: Number) = \"other\"\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package q\n\nimport p.After as make\n\nfun use() = make(1).toString()\n",
                )
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findSamePackageOverloadConflict(
                source,
                primaryClass(source),
                "After",
                listOf(source, functions, usage),
            )

            assertEquals("Usage.kt", conflict?.virtualFile?.name)
        }
    }

    /**
     * Verifies a package-qualified call of another package blocks the rename.
     *
     * The caller spells `p.After(1)` without any import, so only the package-qualified form can
     * reveal that the new constructor would win overload resolution.
     */
    fun testFindSamePackageOverloadConflict_reportsPackageQualifiedNewName() {
        withSession(
            name = "external-package-qualified",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun After(n: Number) = \"other\"\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package q\n\nfun use() = p.After(1).toString()\n",
                )
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findSamePackageOverloadConflict(
                source,
                primaryClass(source),
                "After",
                listOf(source, functions, usage),
            )

            assertEquals("Usage.kt", conflict?.virtualFile?.name)
        }
    }

    /**
     * Verifies a caller that binds another package's `After` is left alone.
     *
     * `lib.After` is not visible from `p`, so adding `p.After` cannot change the caller's
     * overload resolution.
     */
    fun testFindSamePackageOverloadConflict_ignoresOtherPackageBinding() {
        withSession(
            name = "external-other-package",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("usage"))
                Files.createDirectories(root.resolve("lib"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("lib/Library.kt"), "package lib\n\nfun After(n: Number) = \"other\"\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package q\n\nimport lib.After\n\nfun use() = After(1).toString()\n",
                )
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val library = session.ktFile(root.resolve("lib/Library.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findSamePackageOverloadConflict(
                source,
                primaryClass(source),
                "After",
                listOf(source, library, usage),
            )

            assertNull("another package's binding keeps winning over the added class", conflict)
        }
    }

    /**
     * Verifies a top-level function with an equivalently spelled parameter type blocks the rename.
     *
     * `Int` and `kotlin.Int` are the same type, so `fun After(n: kotlin.Int)` collides with the
     * renamed `class Before(n: Int)` even though the spellings differ.
     */
    fun testFindRenameConflict_reportsFunctionWithEquivalentQualifiedSignature() {
        withSession(
            name = "qualified-function-signature",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo(val n: Int)\n")
                Files.writeString(root.resolve("source/Functions.kt"), "package p\n\nfun Bar(n: kotlin.Int) = n\n")
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val functions = session.ktFile(root.resolve("source/Functions.kt"))

            val conflict = findRenameConflict(source, primaryClass(source), "Bar", listOf(source, functions))

            assertEquals("Bar", conflict?.name)
        }
    }

    /**
     * Verifies an existing import that would conflict with the rewritten one blocks the rename.
     *
     * The caller imports `p.Foo` and `q.Bar` without using either. After the rename the first
     * directive becomes `import p.Bar`, and Kotlin rejects two imports binding the same name even
     * when neither has a call site.
     */
    fun testFindCallerScopeConflict_reportsExistingConflictingImport() {
        withSession(
            name = "caller-import-conflict",
            prepare = { root ->
                Files.createDirectories(root.resolve("source"))
                Files.createDirectories(root.resolve("other"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("source/Foo.kt"), "package p\n\nclass Foo\n")
                Files.writeString(root.resolve("other/Types.kt"), "package q\n\nclass Bar\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport p.Foo\nimport q.Bar\n\nfun use() = 1\n",
                )
            },
        ) { session, root ->
            val source = session.ktFile(root.resolve("source/Foo.kt"))
            val types = session.ktFile(root.resolve("other/Types.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            val conflict = findCallerScopeConflict(
                source,
                primaryClass(source),
                "Bar",
                listOf(source, types, usage),
            )

            assertEquals("Usage.kt", conflict?.virtualFile?.name)
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
