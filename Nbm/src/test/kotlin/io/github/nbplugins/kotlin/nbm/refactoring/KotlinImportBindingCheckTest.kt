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

    /**
     * Verifies an implicit `provideDelegate` of a delegated property is reported.
     *
     * `val value by 1` calls the `Int.provideDelegate` extension bound through the wildcard
     * import without spelling its name. Adding an explicit import of the moving `Long.provideDelegate`
     * overload outranks the wildcard, so the property would select the imported overload instead.
     */
    fun testHasConflictingReference_reportsImplicitDelegateProvider() {
        withSession(
            name = "implicit-provider",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\n" +
                        "operator fun Int.provideDelegate(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = this\n\n" +
                        "operator fun Int.getValue(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = this\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\n" +
                        "operator fun Long.provideDelegate(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Long = this\n\n" +
                        "operator fun Long.getValue(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Long = this\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.*\n\n" +
                        "fun use(property: kotlin.reflect.KProperty<*>) {\n" +
                        "    val value by 1\n" +
                        "    (1L).provideDelegate(null, property)\n}\n",
                )
            },
        ) { session, root ->
            val moving = session.ktFile(root.resolve("old/Moving.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))
            val imported = moving.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "provideDelegate" }

            assertTrue(
                "the imported provider would outrank the wildcard provider of val value by 1",
                KotlinImportBindingCheck.hasConflictingReference(usage, "provideDelegate", setOf(imported)),
            )
        }
    }

    /**
     * Verifies the implicit setter of a compound indexed write is reported.
     *
     * `sub[0]++` spells only the getter, and K2 does not expose the setter half. Adding an explicit
     * import of the moving `Box.set` extension outranks the wildcard import that currently selects
     * the staying `SubBox.set`, so the write would silently switch to the imported overload.
     */
    fun testHasConflictingReference_reportsImplicitIndexedSetter() {
        withSession(
            name = "implicit-setter",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\n" +
                        "open class SubBox\n\n" +
                        "operator fun SubBox.get(index: Int): Int = 0\n\n" +
                        "operator fun SubBox.set(index: Int, value: Int) {}\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\nclass Box\n\n" +
                        "operator fun Box.set(index: Int, value: Int) {}\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.*\n\nfun use(sub: SubBox) {\n    sub[0]++\n}\n",
                )
            },
        ) { session, root ->
            val moving = session.ktFile(root.resolve("old/Moving.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))
            val imported = moving.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "set" }

            assertTrue(
                "the implicit setter of sub[0]++ would follow the imported set",
                KotlinImportBindingCheck.hasConflictingReference(usage, "set", setOf(imported)),
            )
        }
    }

    /**
     * Verifies an implicit `invoke` call is reported.
     *
     * `(n)()` spells no name for the convention it calls, so the call-site reference cannot be
     * matched by text. Adding an explicit import of the moving `Number.invoke` extension outranks
     * the wildcard import that currently selects the staying `Int.invoke`, and the call would
     * silently switch overloads.
     */
    fun testHasConflictingReference_reportsImplicitInvoke() {
        withSession(
            name = "implicit-invoke",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\noperator fun Int.invoke(): Int = 1\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\noperator fun Number.invoke(): Int = 2\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.*\n\nfun use(n: Int) = (n)()\n",
                )
            },
        ) { session, root ->
            val moving = session.ktFile(root.resolve("old/Moving.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))
            val imported = moving.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "invoke" }

            assertTrue(
                "the imported Number.invoke would outrank the wildcard Int.invoke of (n)()",
                KotlinImportBindingCheck.hasConflictingReference(usage, "invoke", setOf(imported)),
            )
        }
    }

    /**
     * Verifies an implicit `invoke` call keeps an overload the rewritten import stops binding.
     *
     * `(box)()` resolves to the staying `Box.invoke`; rewriting `import old.invoke` to the moved
     * `Number.invoke` drops that overload, so the call would change its target while still
     * compiling.
     */
    fun testHasConflictingImportRewrite_reportsImplicitInvoke() {
        withSession(
            name = "implicit-invoke-rewrite",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\nclass Box\n\noperator fun Box.invoke(): Int = 1\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\noperator fun Number.invoke(): Int = 2\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.invoke\n\nfun use(box: Box) = (box)()\n",
                )
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))
            val movingPaths = setOf(root.resolve("old/Moving.kt").toString())

            assertTrue(
                "the rewritten import stops binding the Box.invoke of (box)()",
                KotlinImportBindingCheck.hasConflictingImportRewrite(
                    usage,
                    FqName("old.invoke"),
                    movingPaths,
                ),
            )
        }
    }

    /**
     * Verifies an implicit `invoke` call would be captured by an outgoing import.
     *
     * The moved file's `(box)()` resolves to the `Box.invoke` that moves with it; adding an import
     * of the staying `old.invoke` outranks it and silently retargets the call.
     */
    fun testHasConflictingOutgoingImport_reportsImplicitInvoke() {
        withSession(
            name = "implicit-invoke-outgoing",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\noperator fun Number.invoke(): Int = 2\n",
                )
                Files.writeString(
                    root.resolve("old/Movers.kt"),
                    "package old\n\nclass Box\n\noperator fun Box.invoke(): Int = 1\n\n" +
                        "fun use(box: Box) = (box)()\n",
                )
            },
        ) { session, root ->
            val movers = session.ktFile(root.resolve("old/Movers.kt"))
            val movingPaths = setOf(root.resolve("old/Movers.kt").toString())

            assertTrue(
                "the outgoing import would retarget the Box.invoke of (box)()",
                KotlinImportBindingCheck.hasConflictingOutgoingImport(
                    movers,
                    FqName("old.invoke"),
                    movingPaths,
                ),
            )
        }
    }

    /**
     * Verifies the composite-scope lookup honors the caller's ignore sets.
     *
     * The file's own `import old.moved` is reported by scope resolution like any other binding,
     * but it is exactly the import being rewritten, so `moved` is not a competing declaration.
     */
    fun testIsNameBoundInScope_ignoresListedImport() {
        withSession(
            name = "ignored-import",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(root.resolve("old/Moved.kt"), "package old\n\nfun moved() = 1\n")
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.moved\n\nfun use() = old.moved()\n",
                )
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))
            val function = usage.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "use" }

            assertTrue(
                "without exclusions the import is a binding",
                KotlinImportBindingCheck.isNameBoundInScope(function, "moved"),
            )
            assertFalse(
                "the import being rewritten is not a competing binding",
                KotlinImportBindingCheck.isNameBoundInScope(
                    function,
                    "moved",
                    ignoredImports = setOf(FqName("old.moved")),
                ),
            )
        }
    }

    /**
     * Verifies an extension called on a property-valued receiver is treated as a value receiver.
     *
     * `Holder.box` is a property access whose type is `Derived`, so `Holder.box.choose()` selects
     * `Derived.choose` by extension overload resolution even though the chain starts with the
     * class-like `Holder`. Classifying the receiver by its leftmost name treats the call as a
     * type-qualified access and approves an import of `Base.choose` that would reselect it.
     */
    fun testHasConflictingReference_reportsPropertyChainValueReceiver() {
        withSession(
            name = "property-chain-receiver",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\n" +
                        "open class Base\n\n" +
                        "open class Derived : Base()\n\n" +
                        "class Holder {\n    companion object {\n        val box: Derived = Derived()\n    }\n}\n\n" +
                        "fun Derived.choose() = 2\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\nfun Base.choose() = 1\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.*\n\nfun use() = Holder.box.choose()\n",
                )
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            assertTrue(
                "an import of the moved overload would reselect it for Holder.box.choose()",
                KotlinImportBindingCheck.hasConflictingReference(usage, "choose", emptySet()),
            )
        }
    }

    /**
     * Verifies the companion shorthand of a class is treated as a value receiver.
     *
     * `Holder.choose()` passes the companion instance, which may extend the extension's declared
     * receiver (`companion object : Derived()`), even though the spelled name is the class itself.
     * K2 reports `Holder` as a class-like symbol, so the class-qualifier shortcut skips it, and an
     * import of a `Base.choose` overload would be approved even though the explicit import outranks
     * the same-package `Derived.choose` and reselects it.
     */
    fun testHasConflictingReference_reportsCompanionShorthandValueReceiver() {
        withSession(
            name = "companion-shorthand-receiver",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\n" +
                        "open class Base\n\n" +
                        "open class Derived : Base()\n\n" +
                        "class Holder {\n    companion object : Derived()\n}\n\n" +
                        "fun Derived.choose() = 2\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\nfun Base.choose() = 1\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.*\n\nfun use() = Holder.choose()\n",
                )
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            assertTrue(
                "an import of the moved overload would reselect it for Holder.choose()",
                KotlinImportBindingCheck.hasConflictingReference(usage, "choose", emptySet()),
            )
        }
    }

    /**
     * Verifies an extension called on a named object is treated as a value receiver.
     *
     * `singleton.choose()` resolves the extension on `Derived`, the object's supertype, not on the
     * object declaration itself. K2 reports the receiver name as a class-like symbol, so the
     * class-qualifier shortcut would skip it; an import of a `Base.choose` overload would then be
     * approved even though extension overload resolution would reselect it for `singleton`.
     */
    fun testHasConflictingImportRewrite_reportsObjectValueReceiver() {
        withSession(
            name = "object-receiver",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\n" +
                        "open class Base\n\n" +
                        "open class Derived : Base()\n\n" +
                        "object singleton : Derived()\n\n" +
                        "fun Derived.choose() = 2\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\nfun Base.choose() = 1\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.choose\nimport old.singleton\n\n" +
                        "fun use() = singleton.choose()\n",
                )
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))
            val movingPaths = setOf(root.resolve("old/Moving.kt").toString())

            assertTrue(
                "an explicit import of the moved overload would reselect it for singleton.choose()",
                KotlinImportBindingCheck.hasConflictingImportRewrite(
                    usage,
                    FqName("old.choose"),
                    movingPaths,
                ),
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

    /**
     * Verifies a retained delegate accessor with a generic receiver is reported.
     *
     * The staying `Box<T>.getValue` accepts `Box<Int>` only after inferring `T`. A raw subtype
     * test rejects it, so the added import of the moving `Any.getValue` would look harmless even
     * though an explicit import outranks the wildcard binding the property uses today.
     */
    fun testHasConflictingReference_reportsGenericDelegateAccessor() {
        withSession(
            name = "generic-delegate-accessor",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\nclass Box<T>\n\n" +
                        "operator fun <T> Box<T>.getValue(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = 1\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\n" +
                        "operator fun Any.getValue(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = 2\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.*\n\n" +
                        "fun use() {\n    val value by Box<Int>()\n    value\n}\n",
                )
            },
        ) { session, root ->
            val moving = session.ktFile(root.resolve("old/Moving.kt"))
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))
            val imported = moving.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "getValue" }

            assertTrue(
                "the imported accessor would outrank the generic wildcard accessor",
                KotlinImportBindingCheck.hasConflictingReference(usage, "getValue", setOf(imported)),
            )
        }
    }

    /**
     * Verifies the binding helper follows the member provider overload resolution selects.
     *
     * `Box` declares `provideDelegate(Any?, ...)` returning the staying `General` and
     * `provideDelegate(Host, ...)` returning the moving `Specific`. The property inside `Host`
     * binds `Specific.getValue`, so importing that moved accessor captures nothing and must not be
     * reported. Matching the first declared member would bind `General.getValue` and report a
     * false conflict.
     */
    fun testHasConflictingReference_usesSelectedMemberProviderOverload() {
        withSession(
            name = "member-provider-overload-selection",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\nclass General\n\n" +
                        "operator fun General.getValue(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = 1\n\n" +
                        "class Host {\n    val value: Int by Box()\n}\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\nclass Specific\n\nclass Box {\n" +
                        "    operator fun provideDelegate(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): General = General()\n" +
                        "    operator fun provideDelegate(" +
                        "thisRef: Host, property: kotlin.reflect.KProperty<*>): Specific = Specific()\n" +
                        "}\n\n" +
                        "operator fun Specific.getValue(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = 2\n",
                )
            },
        ) { session, root ->
            val staying = session.ktFile(root.resolve("old/Staying.kt"))
            val moving = session.ktFile(root.resolve("old/Moving.kt"))
            val accessor = moving.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "getValue" }

            assertFalse(
                "the property already binds the moved accessor, so importing it captures nothing",
                KotlinImportBindingCheck.hasConflictingReference(staying, "getValue", setOf(accessor)),
            )
        }
    }

    /**
     * Verifies a generic retained accessor blocks rewriting a direct import.
     *
     * `import old.getValue` binds both the staying `Box<T>.getValue` and the moving
     * `Any.getValue`. Rewriting the directive to the new package keeps only the latter, so the
     * property would silently select the moved declaration.
     */
    fun testHasConflictingImportRewrite_reportsGenericDelegateAccessor() {
        withSession(
            name = "generic-delegate-rewrite",
            prepare = { root ->
                Files.createDirectories(root.resolve("old"))
                Files.createDirectories(root.resolve("usage"))
                Files.writeString(
                    root.resolve("old/Staying.kt"),
                    "package old\n\nclass Box<T>\n\n" +
                        "operator fun <T> Box<T>.getValue(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = 1\n",
                )
                Files.writeString(
                    root.resolve("old/Moving.kt"),
                    "package old\n\n" +
                        "operator fun Any.getValue(" +
                        "thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = 2\n",
                )
                Files.writeString(
                    root.resolve("usage/Usage.kt"),
                    "package usage\n\nimport old.Box\nimport old.getValue\n\n" +
                        "fun use() {\n    val value by Box<Int>()\n    value\n}\n",
                )
            },
        ) { session, root ->
            val usage = session.ktFile(root.resolve("usage/Usage.kt"))

            assertTrue(
                "rewriting the import would drop the generic accessor the property uses",
                KotlinImportBindingCheck.hasConflictingImportRewrite(
                    usage,
                    FqName("old.getValue"),
                    setOf(root.resolve("old/Moving.kt").toString()),
                ),
            )
        }
    }

    /**
     * Verifies a classifier declared in a sibling file of the caller's package is reported.
     *
     * Rewriting a type reference to `After` in package `q` resolves to the sibling `q.After`
     * rather than the renamed `p.After`, even though the caller file itself declares neither.
     */
    fun testIsClassifierBoundInScope_reportsSiblingClassifierInCallerPackage() {
        withSession(
            name = "classifier-sibling-package",
            prepare = { root ->
                Files.createDirectories(root.resolve("p"))
                Files.createDirectories(root.resolve("q"))
                Files.writeString(root.resolve("p/Before.kt"), "package p\n\nclass Before\n")
                Files.writeString(root.resolve("q/After.kt"), "package q\n\nclass After\n")
                Files.writeString(
                    root.resolve("q/Caller.kt"),
                    "package q\n\nimport p.*\n\nfun consume(x: Before) = x.toString()\n",
                )
            },
        ) { session, root ->
            val caller = session.ktFile(root.resolve("q/Caller.kt"))
            val typeElement = caller.declarations.filterIsInstance<KtNamedFunction>().single()
                .valueParameters.single().typeReference?.typeElement ?: error("No type element")

            assertTrue(
                "a sibling classifier of the caller's package would capture the rewritten type",
                KotlinImportBindingCheck.isClassifierBoundInScope(typeElement, "After"),
            )
        }
    }

    /**
     * Verifies a classifier supplied by a wildcard import is reported.
     *
     * Neither `lib.After` nor `p.*` is spelled by a directive that binds the name directly, but
     * the composite scope resolves `After` through `import lib.*`, so the rewritten type would
     * bind it instead of the renamed declaration.
     */
    fun testIsClassifierBoundInScope_reportsClassifierFromWildcardImport() {
        withSession(
            name = "classifier-wildcard-package",
            prepare = { root ->
                for (directory in listOf("p", "lib", "r")) {
                    Files.createDirectories(root.resolve(directory))
                }
                Files.writeString(root.resolve("p/Before.kt"), "package p\n\nclass Before\n")
                Files.writeString(root.resolve("lib/After.kt"), "package lib\n\nclass After\n")
                Files.writeString(
                    root.resolve("r/Caller.kt"),
                    "package r\n\nimport lib.*\nimport p.*\n\nfun consume(x: Before) = x.toString()\n",
                )
            },
        ) { session, root ->
            val caller = session.ktFile(root.resolve("r/Caller.kt"))
            val typeElement = caller.declarations.filterIsInstance<KtNamedFunction>().single()
                .valueParameters.single().typeReference?.typeElement ?: error("No type element")

            assertTrue(
                "a wildcard-imported classifier would capture the rewritten type",
                KotlinImportBindingCheck.isClassifierBoundInScope(typeElement, "After"),
            )
        }
    }

    /** Verifies a name no scope binds is not reported as a capture. */
    fun testIsClassifierBoundInScope_ignoresUnboundName() {
        withSession(
            name = "classifier-unbound-package",
            prepare = { root ->
                Files.createDirectories(root.resolve("p"))
                Files.createDirectories(root.resolve("q"))
                Files.writeString(root.resolve("p/Before.kt"), "package p\n\nclass Before\n")
                Files.writeString(
                    root.resolve("q/Caller.kt"),
                    "package q\n\nimport p.*\n\nfun consume(x: Before) = x.toString()\n",
                )
            },
        ) { session, root ->
            val caller = session.ktFile(root.resolve("q/Caller.kt"))
            val typeElement = caller.declarations.filterIsInstance<KtNamedFunction>().single()
                .valueParameters.single().typeReference?.typeElement ?: error("No type element")

            assertFalse(
                "no scope binds the new name, so the rename is safe",
                KotlinImportBindingCheck.isClassifierBoundInScope(typeElement, "After"),
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
