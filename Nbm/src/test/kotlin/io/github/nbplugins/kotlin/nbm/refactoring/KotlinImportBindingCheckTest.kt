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
import io.github.nbplugins.kotlin.refactoring.KotlinImportBindingCheck
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import utils.KotlinTestCase
import java.nio.file.Files
import java.nio.file.Path

/** Integration tests for the import-binding collision check used by the refactorings. */
class KotlinImportBindingCheckTest : KotlinTestCase("KotlinImportBindingCheckTest", "moveFile") {

    /** Verifies an unqualified reference to another top-level declaration is reported. */
    fun testHasConflictingReference_reportsUnqualifiedBinding() {
        withSession(
            name = "unqualified",
            prepare = { root ->
                Files.createDirectories(root.resolve("other"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("other/Types.kt"), "package q\n\nclass Bar\n")
                Files.writeString(root.resolve("usage/Usage.kt"), "package usage\n\nimport q.Bar\n\nfun use() = Bar()\n")
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            assertTrue(
                "an unqualified Bar() would be rebound by the import",
                KotlinImportBindingCheck.hasConflictingReference(usage, "Bar", emptySet()),
            )
        }
    }

    /** Verifies a reference the import is meant to retarget is not reported. */
    fun testHasConflictingReference_ignoresExcludedDeclaration() {
        withSession(
            name = "excluded",
            prepare = { root ->
                Files.createDirectories(root.resolve("other"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("other/Types.kt"), "package q\n\nclass Bar\n")
                Files.writeString(root.resolve("usage/Usage.kt"), "package usage\n\nimport q.Bar\n\nfun use() = Bar()\n")
            },
        ) { session, root ->
            val types = session.ktFile(root.resolve("other/Types.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))
            val declared = types.declarations.filterIsInstance<KtClass>().single()

            assertFalse(
                "the import exists to retarget this very reference",
                KotlinImportBindingCheck.hasConflictingReference(usage, "Bar", setOf(declared)),
            )
        }
    }

    /** Verifies a qualified access keeps resolving through its receiver. */
    fun testHasConflictingReference_ignoresQualifiedAccess() {
        withSession(
            name = "qualified",
            prepare = { root ->
                Files.createDirectories(root.resolve("other"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("other/Types.kt"), "package q\n\nclass Bar\n")
                Files.writeString(root.resolve("usage/Usage.kt"), "package usage\n\nfun use() = q.Bar()\n")
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            assertFalse(
                "a qualified access is not rebound by an import",
                KotlinImportBindingCheck.hasConflictingReference(usage, "Bar", emptySet()),
            )
        }
    }

    /** Verifies a local binding that shadows the name is not an import collision. */
    fun testHasConflictingReference_ignoresLocalBinding() {
        withSession(
            name = "local",
            prepare = { root ->
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nfun use(): Int { val Bar = 1; return Bar }\n",
                )
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            assertFalse(
                "a local variable shadowing the name is unaffected by an import",
                KotlinImportBindingCheck.hasConflictingReference(usage, "Bar", emptySet()),
            )
        }
    }

    /**
     * Verifies an extension call on a value receiver is reported.
     *
     * An explicit import outranks the wildcard binding that selects the extension today, so the
     * call would silently switch to the imported overload.
     */
    fun testHasConflictingReference_reportsExtensionOnValueReceiver() {
        withSession(
            name = "extension",
            prepare = { root ->
                Files.createDirectories(root.resolve("other"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("other/Types.kt"), "package q\n\nfun Int.choose() = 1\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport q.*\n\nfun use() = 1.choose()\n",
                )
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            assertTrue(
                "an explicit import would reselect the extension used by this call",
                KotlinImportBindingCheck.hasConflictingReference(usage, "choose", emptySet()),
            )
        }
    }

    /**
     * Verifies a convention reference to a moving declaration is reported for an outgoing import.
     *
     * `n[0]` calls the `Int.get` extension that moves with the file without spelling its name.
     * Adding `import old.get` would make the import outrank the same-package binding and select
     * the staying `Number.get` overload instead.
     */
    fun testHasConflictingOutgoingImport_reportsConventionReferenceToMovingDeclaration() {
        withSession(
            name = "outgoing-convention",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.writeString(
                    root.resolve("old/Operators.kt"),
                    "package old\n\noperator fun Number.get(index: Int) = index\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\noperator fun Int.get(index: Int) = -index\n",
                )
                Files.writeString(root.resolve("old/Usage.kt"), "package old\n\nfun use(n: Int) = n[0]\n")
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("old/Usage.kt"))
            val movingPaths = setOf(root.resolve("old/Moving.kt").toString())

            assertTrue(
                "importing old.get would reselect the staying overload for n[0]",
                KotlinImportBindingCheck.hasConflictingOutgoingImport(usage, FqName("old.get"), movingPaths),
            )
        }
    }

    /** Verifies a convention reference already bound to the staying declaration is not reported. */
    fun testHasConflictingOutgoingImport_ignoresConventionReferenceToStayingDeclaration() {
        withSession(
            name = "outgoing-convention-staying",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.writeString(
                    root.resolve("old/Operators.kt"),
                    "package old\n\noperator fun Number.get(index: Int) = index\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\noperator fun Int.get(index: Int) = -index\n",
                )
                Files.writeString(root.resolve("old/Usage.kt"), "package old\n\nfun use(n: Number) = n[0]\n")
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("old/Usage.kt"))
            val movingPaths = setOf(root.resolve("old/Moving.kt").toString())

            assertFalse(
                "the accessor already binds the declaration the import would provide",
                KotlinImportBindingCheck.hasConflictingOutgoingImport(usage, FqName("old.get"), movingPaths),
            )
        }
    }

    /** Verifies a member function of an enclosing class binds the name lexically. */
    fun testIsNameBoundInScope_reportsMemberFunction() {
        withSession(
            name = "member",
            prepare = { root ->
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nclass Caller {\n    fun After() = 1\n    fun make() = 2\n}\n",
                )
            },
        ) { session, root ->
            val make = session.ktFile(root.resolve("usage/Usage.kt"))
                .declarations.filterIsInstance<KtClass>().single { type -> type.name == "Caller" }
                .declarations.filterIsInstance<KtNamedFunction>().single { function -> function.name == "make" }

            assertTrue(
                "the enclosing class already declares the name",
                KotlinImportBindingCheck.isNameBoundInScope(make, "After"),
            )
        }
    }

    /** Verifies an ordinary import binds the imported declaration's simple name. */
    fun testIsNameBoundInScope_reportsImportedBinding() {
        withSession(
            name = "imported",
            prepare = { root ->
                Files.createDirectories(root.resolve("other"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("other/Types.kt"), "package other\n\nobject dest\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport other.dest\n\nfun use() = 1\n",
                )
            },
        ) { session, root ->
            val use = session.ktFile(root.resolve("usage/Usage.kt"))
                .declarations.filterIsInstance<KtNamedFunction>().single()

            assertTrue(
                "an import binds the name even without an alias",
                KotlinImportBindingCheck.isNameBoundInScope(use, "dest"),
            )
        }
    }

    /** Verifies an unrelated name is not reported as bound. */
    fun testIsNameBoundInScope_ignoresUnrelatedName() {
        withSession(
            name = "unrelated",
            prepare = { root ->
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("usage/Usage.kt"), "package usage\n\nfun use() = 1\n")
            },
        ) { session, root ->
            val use = session.ktFile(root.resolve("usage/Usage.kt"))
                .declarations.filterIsInstance<KtNamedFunction>().single()

            assertFalse(
                "nothing in the file binds the requested name",
                KotlinImportBindingCheck.isNameBoundInScope(use, "missing"),
            )
        }
    }

    /**
     * Verifies a primary-constructor parameter is reported inside a property initializer.
     *
     * A non-property constructor parameter is visible in initializers without being a member, so
     * rewriting a name into the initializer would call the parameter instead of the renamed class.
     */
    fun testIsNameBoundInScope_reportsPrimaryConstructorParameterInInitializer() {
        withSession(
            name = "constructor-initializer",
            prepare = { root ->
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nclass Caller(After: () -> String) {\n" +
                        "    val value = Before().toString()\n}\n\nclass Before\n",
                )
            },
        ) { session, root ->
            val property = session.ktFile(root.resolve("usage/Usage.kt"))
                .declarations.filterIsInstance<KtClass>().single { type -> type.name == "Caller" }
                .declarations.filterIsInstance<KtProperty>().single()

            assertTrue(
                "the constructor parameter is visible in the initializer",
                KotlinImportBindingCheck.isNameBoundInScope(property, "After"),
            )
        }
    }

    /** Verifies a primary-constructor parameter is not visible inside a member function body. */
    fun testIsNameBoundInScope_ignoresPrimaryConstructorParameterInMemberFunction() {
        withSession(
            name = "constructor-member",
            prepare = { root ->
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nclass Caller(After: () -> String) {\n" +
                        "    fun make() = 1\n}\n\nclass Before\n",
                )
            },
        ) { session, root ->
            val make = session.ktFile(root.resolve("usage/Usage.kt"))
                .declarations.filterIsInstance<KtClass>().single { type -> type.name == "Caller" }
                .declarations.filterIsInstance<KtNamedFunction>().single { function -> function.name == "make" }

            assertFalse(
                "a member function does not see the primary-constructor parameters",
                KotlinImportBindingCheck.isNameBoundInScope(make, "After"),
            )
        }
    }

    /**
     * Verifies an implicit lambda parameter binds the name `it`.
     *
     * A lambda without declared parameters receives `it` implicitly, so rewriting a reference
     * inside it to `it` would call the lambda parameter rather than the renamed declaration.
     */
    fun testIsNameBoundInScope_reportsImplicitLambdaParameter() {
        withSession(
            name = "implicit-it",
            prepare = { root ->
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nfun use() = listOf({ \"other\" }).map { Before().toString() }\n\n" +
                        "class Before\n",
                )
            },
        ) { session, root ->
            val file = session.ktFile(root.resolve("usage/Usage.kt"))
            val reference = PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java)
                .single { expression ->
                    expression.getReferencedName() == "Before" && expression.parent is KtCallExpression
                }

            assertTrue(
                "the lambda's implicit it parameter is in scope",
                KotlinImportBindingCheck.isNameBoundInScope(reference, "it"),
            )
        }
    }

    /** Verifies a lambda with declared parameters does not introduce `it`. */
    fun testIsNameBoundInScope_ignoresLambdaWithDeclaredParameter() {
        withSession(
            name = "declared-parameter",
            prepare = { root ->
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nfun use() = listOf(1).map { value -> Before().toString() }\n\n" +
                        "class Before\n",
                )
            },
        ) { session, root ->
            val file = session.ktFile(root.resolve("usage/Usage.kt"))
            val reference = PsiTreeUtil.findChildrenOfType(file, KtNameReferenceExpression::class.java)
                .single { expression ->
                    expression.getReferencedName() == "Before" && expression.parent is KtCallExpression
                }

            assertFalse(
                "a lambda with a declared parameter has no implicit it",
                KotlinImportBindingCheck.isNameBoundInScope(reference, "it"),
            )
        }
    }

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
            println("kotlin-stdlib not on test classpath — skipping import binding integration test")
            return
        }
        val root = Files.createTempDirectory("nbkotlin-import-binding-$name")
        try {
            prepare(root)
            val session = KotlinAnalysisAPISession.createWithJars(
                moduleName = "import-binding-$name-integration",
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
