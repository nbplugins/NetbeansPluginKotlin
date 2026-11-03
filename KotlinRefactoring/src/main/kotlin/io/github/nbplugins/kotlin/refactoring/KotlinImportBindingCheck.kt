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
package io.github.nbplugins.kotlin.refactoring

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.compat.getClassOrObjectSymbol
import org.jetbrains.kotlin.analysis.api.components.KaScopeImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.components.expandedSymbol
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.isSubtypeOf
import org.jetbrains.kotlin.analysis.api.components.memberScope
import org.jetbrains.kotlin.analysis.api.components.packageScope
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbol
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.components.scopeContext
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.findPackage
import org.jetbrains.kotlin.analysis.api.symbols.findTopLevelCallables
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Detects references that inserting an explicit import would silently retarget.
 *
 * An explicit import takes precedence over wildcard imports, other explicit imports are a
 * conflicting import, and imports also win over declarations of the importing file's own package.
 * A refactoring that adds or rewrites an import must therefore prove that every unqualified
 * reference the imported name could capture already points at the declaration it is meant for;
 * otherwise the refactoring would change which declaration is called while still compiling.
 */
object KotlinImportBindingCheck {

    /**
     * Tells whether importing [name] into [file] would capture a reference to another declaration.
     *
     * Only unqualified references are considered: a selector keeps resolving through its receiver
     * whatever the file imports. Locals, parameters, and members keep binding to themselves as
     * well, so only symbols that an import can actually shadow count.
     *
     * @param file caller that is about to import a declaration named [name]
     * @param name simple name the import binds
     * @param excludedDeclarations declarations the import is meant to retarget; references that
     * resolve to one of them, or to a constructor of a class among them, are not conflicts
     * @return `true` when some unqualified reference in [file] would change its target
     */
    fun hasConflictingReference(
        file: KtFile,
        name: String,
        excludedDeclarations: Set<PsiElement>,
    ): Boolean = runCatching {
        analyze(file) {
            var conflicting = false
            // Only an extension can act as a delegate accessor, so importing a class or property
            // of that name cannot change which accessor a delegated property selects.
            val importsAccessor = excludedDeclarations.any { declaration ->
                declaration is KtNamedFunction && declaration.name == name &&
                    declaration.receiverTypeReference != null
            }
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    super.visitSimpleNameExpression(expression)
                    if (conflicting) return
                    val referencedName = expression.getReferencedName()
                    // A convention operator spells `plus` as `+`, so an operation reference can
                    // still bind the name the import introduces even though its text differs.
                    if (referencedName != name && expression !is KtOperationReferenceExpression) return
                    // A selector resolves through its receiver, except for an extension call on
                    // a value: an explicit import outranks the wildcard or same-package binding
                    // that currently selects the extension.
                    if (expression.isQualifiedSelector() && !expression.isValueReceiverExtension()) return
                    if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                    val symbol = expression.references.filterIsInstance<KtReference>().firstOrNull()
                        ?.resolveToSymbol() ?: return
                    if (referencedName != name && symbol.declaredName() != name) return
                    if (symbol.belongsTo(excludedDeclarations)) return
                    if (!symbol.isTopLevelBinding()) return
                    conflicting = true
                }

                // A delegated property spells none of its operators either: `val value by 1`
                // calls a top-level `getValue` that an added explicit import outranks.
                override fun visitProperty(property: KtProperty) {
                    super.visitProperty(property)
                    if (conflicting || !importsAccessor) return
                    runCatching { property.delegateAccessorCandidates(name) }.getOrNull()?.forEach { symbol ->
                        if (!symbol.belongsTo(excludedDeclarations) && symbol.isTopLevelBinding()) {
                            conflicting = true
                        }
                    }
                }

                // Convention references spell no name for the callable they invoke: `n[0]` calls
                // `get`, `val (a) = box` calls `componentN`, and `for (x in box)` calls
                // `iterator`. An import of that name can retarget them exactly like a spelled
                // reference, so they go through the same check.
                override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                    super.visitArrayAccessExpression(expression)
                    if (!conflicting) {
                        expression.capturesTopLevelName(
                            name,
                            { symbol -> !symbol.belongsTo(excludedDeclarations) },
                        ) { conflicting = true }
                    }
                    // A compound indexed write spells only the getter; its implicit setter goes
                    // through the same check because an import of `set` outranks the wildcard.
                    if (!conflicting && name == "set") {
                        runCatching { expression.implicitSetterCandidates() }.getOrNull()?.forEach { symbol ->
                            if (!symbol.belongsTo(excludedDeclarations) && symbol.isTopLevelBinding()) {
                                conflicting = true
                            }
                        }
                    }
                }

                override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
                    super.visitDestructuringDeclarationEntry(entry)
                    if (!conflicting) {
                        entry.capturesTopLevelName(
                            name,
                            { symbol -> !symbol.belongsTo(excludedDeclarations) },
                        ) { conflicting = true }
                    }
                }

                override fun visitForExpression(expression: KtForExpression) {
                    super.visitForExpression(expression)
                    if (!conflicting) {
                        expression.capturesTopLevelName(
                            name,
                            { symbol -> !symbol.belongsTo(excludedDeclarations) },
                        ) { conflicting = true }
                    }
                }

                // An implicit invocation `(n)()` spells no name for the `invoke` operator it
                // calls: the invocation reference belongs to the call expression rather than to
                // any spelled name. An explicit callee is already handled above.
                override fun visitCallExpression(expression: KtCallExpression) {
                    super.visitCallExpression(expression)
                    if (!conflicting && name == "invoke") {
                        expression.capturesTopLevelName(
                            name,
                            { symbol -> !symbol.belongsTo(excludedDeclarations) },
                        ) { conflicting = true }
                    }
                }
            })
            conflicting
        }
    }.getOrDefault(false)

    /**
     * Tells whether rewriting an import of [imported] to another package would rebind references.
     *
     * `import old.choose` binds the whole overload set of that name, so following the declaration
     * to a new package drops every overload that stays behind. A reference that currently uses
     * one of those overloads would then resolve to the moved declaration instead — or stop
     * resolving at all — while the file still compiles.
     *
     * Only references this import actually selects are considered: a chain qualified by the
     * package spelling keeps resolving on its own, and qualified selectors keep resolving through
     * their receiver unless they are extension calls on a value. References that resolve into one
     * of [movingPaths] follow the import and are fine.
     *
     * @param file caller whose import directive is being rewritten
     * @param imported original FQN of the moved declaration
     * @param movingPaths paths of the files whose package changes
     * @param boundName name the directive binds at its call sites — the alias when the directive
     * is aliased, the declaration's simple name otherwise
     * @param aliased `true` when the rewritten directive binds an alias; an aliased directive
     * provides no convention operator under the declaration's own name
     * @return `true` when a reference would bind to the moved declaration instead of its own
     */
    fun hasConflictingImportRewrite(
        file: KtFile,
        imported: FqName,
        movingPaths: Set<String>,
        boundName: String = imported.shortName().asString(),
        aliased: Boolean = false,
    ): Boolean = runCatching {
        analyze(file) {
            var conflicting = false
            val importedName = imported.shortName().asString()
            // Only an extension can act as a delegate accessor, and an aliased directive provides
            // no operator under the declaration's own name.
            val rewritesAccessor = !aliased && isExtensionCallable(imported)
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    super.visitSimpleNameExpression(expression)
                    if (conflicting) return
                    val referencedName = expression.getReferencedName()
                    // A convention operator spells `plus` as `+`, so an operation reference can
                    // still bind the name the rewritten import selects. An aliased directive is
                    // spelled only as its alias at call sites.
                    if (referencedName != boundName && expression !is KtOperationReferenceExpression) return
                    if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                    if (expression.isQualifiedSelector() && !expression.isValueReceiverExtension()) return
                    val symbol = expression.references.filterIsInstance<KtReference>().firstOrNull()
                        ?.resolveToSymbol() ?: return
                    if (referencedName != boundName && symbol.declaredName() != importedName) return
                    val path = symbol.psi?.containingFile?.virtualFile?.path
                    if (path != null && path in movingPaths) return
                    if (!symbol.isTopLevelBinding()) return
                    if (symbol.declaredPackage() != imported.parent()) return
                    conflicting = true
                }

                // A delegated property spells no accessor either, so `val value by 1` keeps
                // binding the overload the rewritten directive stops importing.
                override fun visitProperty(property: KtProperty) {
                    super.visitProperty(property)
                    if (conflicting || !rewritesAccessor) return
                    runCatching { property.delegateAccessorCandidates(importedName) }.getOrNull()?.forEach { symbol ->
                        if (symbol.isTopLevelBinding() && symbol.staysInOriginalPackage(imported, movingPaths)) {
                            conflicting = true
                        }
                    }
                }

                // A convention call spells no name, so `n[0]` (`get`) keeps binding an overload
                // the rewritten directive stops importing even though no reference names it.
                // An aliased directive binds only its alias, so it provides no convention operator.
                override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                    super.visitArrayAccessExpression(expression)
                    if (!conflicting && !aliased) {
                        expression.capturesTopLevelName(
                            importedName,
                            { symbol -> symbol.staysInOriginalPackage(imported, movingPaths) },
                        ) { conflicting = true }
                        // The setter half of a compound write is implicit; it loses the overload
                        // the rewritten directive stops importing just like the getter does.
                        if (!conflicting && importedName == "set") {
                            runCatching { expression.implicitSetterCandidates() }.getOrNull()?.forEach { symbol ->
                                if (symbol.isTopLevelBinding() &&
                                    symbol.staysInOriginalPackage(imported, movingPaths)
                                ) {
                                    conflicting = true
                                }
                            }
                        }
                    }
                }

                override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
                    super.visitDestructuringDeclarationEntry(entry)
                    if (!conflicting && !aliased) {
                        entry.capturesTopLevelName(
                            importedName,
                            { symbol -> symbol.staysInOriginalPackage(imported, movingPaths) },
                        ) { conflicting = true }
                    }
                }

                override fun visitForExpression(expression: KtForExpression) {
                    super.visitForExpression(expression)
                    if (!conflicting && !aliased) {
                        expression.capturesTopLevelName(
                            importedName,
                            { symbol -> symbol.staysInOriginalPackage(imported, movingPaths) },
                        ) { conflicting = true }
                    }
                }

                // An implicit invocation `(n)()` spells no name for the `invoke` operator it
                // calls: the invocation reference belongs to the call expression rather than to
                // any spelled name. An aliased directive binds only its alias, so it provides no
                // convention operator.
                override fun visitCallExpression(expression: KtCallExpression) {
                    super.visitCallExpression(expression)
                    if (!conflicting && !aliased && importedName == "invoke") {
                        expression.capturesTopLevelName(
                            importedName,
                            { symbol -> symbol.staysInOriginalPackage(imported, movingPaths) },
                        ) { conflicting = true }
                    }
                }
            })
            conflicting
        }
    }.getOrDefault(false)

    /**
     * Tells whether adding [imported] to a moved file would capture one of its own references.
     *
     * An outgoing import keeps a moved file bound to a declaration left in its former package,
     * but an explicit import outranks the same-package declarations of the file's new package and
     * any wildcard import. A reference that currently resolves to a declaration moving with the
     * file, or to a declaration of another package, would silently bind to the staying declaration
     * instead.
     *
     * @param file moved file that is about to receive the import
     * @param imported FQN of the declaration that stays behind
     * @param movingPaths paths of the files whose package changes
     * @return `true` when an unqualified reference would bind to the staying declaration instead
     */
    fun hasConflictingOutgoingImport(
        file: KtFile,
        imported: FqName,
        movingPaths: Set<String>,
    ): Boolean = runCatching {
        analyze(file) {
            var conflicting = false
            val importedName = imported.shortName().asString()
            // Only an extension can act as a delegate accessor, so importing another kind of
            // declaration of that name leaves the property's resolution untouched.
            val importsAccessor = isExtensionCallable(imported)
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    super.visitSimpleNameExpression(expression)
                    if (conflicting) return
                    val referencedName = expression.getReferencedName()
                    if (referencedName != importedName && expression !is KtOperationReferenceExpression) return
                    if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                    if (expression.isQualifiedSelector() && !expression.isValueReceiverExtension()) return
                    val symbol = expression.references.filterIsInstance<KtReference>().firstOrNull()
                        ?.resolveToSymbol() ?: return
                    if (referencedName != importedName && symbol.declaredName() != importedName) return
                    if (!symbol.isTopLevelBinding()) return
                    if (symbol.isCapturedByOutgoingImport(imported, movingPaths)) conflicting = true
                }

                // A delegated property spells no accessor, so the added import can retarget the
                // operator its delegate expression selects.
                override fun visitProperty(property: KtProperty) {
                    super.visitProperty(property)
                    if (conflicting || !importsAccessor) return
                    runCatching { property.delegateAccessorCandidates(importedName) }.getOrNull()?.forEach { symbol ->
                        if (symbol.isTopLevelBinding() &&
                            symbol.isCapturedByOutgoingImport(imported, movingPaths)
                        ) {
                            conflicting = true
                        }
                    }
                }

                // A convention call spells no name, so `n[0]` (`get`) keeps binding the same-package
                // overload while the file moves; the added import would outrank it instead.
                override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                    super.visitArrayAccessExpression(expression)
                    if (!conflicting) {
                        expression.capturesTopLevelName(
                            importedName,
                            { symbol -> symbol.isCapturedByOutgoingImport(imported, movingPaths) },
                        ) { conflicting = true }
                        // The implicit setter of a compound write keeps binding through the
                        // wildcard while the file moves; the added import would outrank it.
                        if (!conflicting && importedName == "set") {
                            runCatching { expression.implicitSetterCandidates() }.getOrNull()?.forEach { symbol ->
                                if (symbol.isTopLevelBinding() &&
                                    symbol.isCapturedByOutgoingImport(imported, movingPaths)
                                ) {
                                    conflicting = true
                                }
                            }
                        }
                    }
                }

                override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
                    super.visitDestructuringDeclarationEntry(entry)
                    if (!conflicting) {
                        entry.capturesTopLevelName(
                            importedName,
                            { symbol -> symbol.isCapturedByOutgoingImport(imported, movingPaths) },
                        ) { conflicting = true }
                    }
                }

                override fun visitForExpression(expression: KtForExpression) {
                    super.visitForExpression(expression)
                    if (!conflicting) {
                        expression.capturesTopLevelName(
                            importedName,
                            { symbol -> symbol.isCapturedByOutgoingImport(imported, movingPaths) },
                        ) { conflicting = true }
                    }
                }

                // An implicit invocation `(n)()` spells no name for the `invoke` operator it
                // calls: the invocation reference belongs to the call expression rather than to
                // any spelled name. An explicit callee is already handled above.
                override fun visitCallExpression(expression: KtCallExpression) {
                    super.visitCallExpression(expression)
                    if (!conflicting && importedName == "invoke") {
                        expression.capturesTopLevelName(
                            importedName,
                            { symbol -> symbol.isCapturedByOutgoingImport(imported, movingPaths) },
                        ) { conflicting = true }
                    }
                }
            })
            conflicting
        }
    }.getOrDefault(false)

    /**
     * Tells whether [file] already refers to a same-package declaration named [name].
     *
     * Adding a top-level declaration to a package changes overload resolution for unqualified
     * references to that name, so a same-package reference that currently resolves to another
     * top-level callable can silently bind to the added declaration. A reference bound through a
     * star or default import is outranked by the same-package declaration in the same way. An
     * explicit import of the name from elsewhere keeps winning over the package, so it is not a
     * conflict.
     *
     * @param file caller that already names the declaration
     * @param name simple name of the declaration being added
     * @param packageName package the declaration is added to
     * @param excludedDeclarations declarations the added name is meant for
     * @return `true` when a reference to another same-package declaration would be affected
     */
    fun hasSamePackageReferenceToOtherDeclaration(
        file: KtFile,
        name: String,
        packageName: FqName,
        excludedDeclarations: Set<PsiElement>,
    ): Boolean = runCatching {
        analyze(file) {
            var conflicting = false
            file.accept(object : KtTreeVisitorVoid() {
                override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                    super.visitSimpleNameExpression(expression)
                    if (conflicting) return
                    if (expression.getReferencedName() != name) return
                    if (expression.isQualifiedSelector()) return
                    if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                    val symbol = expression.references.filterIsInstance<KtReference>().firstOrNull()
                        ?.resolveToSymbol() ?: return
                    if (symbol.belongsTo(excludedDeclarations)) return
                    if (!symbol.isTopLevelBinding()) return
                    // A reference that already binds a same-package declaration changes overload
                    // resolution once the new declaration joins the package. A reference bound
                    // through a star or default import is outranked by the package in the same
                    // way, while an explicit import keeps winning, so only star-bound references
                    // without an explicit import are affected.
                    if (symbol.declaredPackage() == packageName ||
                        file.importDirectives.none { directive -> directive.bindsName(name, emptySet()) }
                    ) {
                        conflicting = true
                    }
                }
            })
            conflicting
        }
    }.getOrDefault(false)

    /**
     * Tells whether [name] is already bound to a classifier at [element].
     *
     * Kotlin resolves type references in a namespace separate from values, so a rewritten type
     * position can only be captured by a class, object, type alias, or type parameter of that
     * name — not by a function or property. Imports are treated conservatively because telling a
     * classifier import from a callable import needs resolution.
     *
     * @param element position whose rewritten text denotes a type
     * @param name new simple name the position is about to spell
     * @return `true` when another classifier would win over the intended target
     */
    fun isClassifierBoundInScope(element: KtElement, name: String): Boolean {
        val file = element.containingKtFile
        if (file.importDirectives.any { directive -> directive.bindsName(name, emptySet()) }) return true
        if (file.declarations.any { declaration -> declaration.declaresClassifierName(name) }) return true
        var child: PsiElement = element
        var current: PsiElement? = element.parent
        while (current != null) {
            val bound = when (current) {
                // A local class or type alias is visible only after its declaration and only
                // inside the block that declares it; it is not a member of any enclosing class.
                is KtBlockExpression -> current.statements
                    .takeWhile { statement -> statement.textRange.startOffset < child.textRange.startOffset }
                    .any { statement ->
                        (statement as? KtDeclaration)?.declaresClassifierName(name) == true
                    }
                is KtClassBody -> current.declarations.any { declaration -> declaration.declaresClassifierName(name) }
                is KtTypeParameterListOwner -> current.typeParameters.any { parameter -> parameter.name == name }
                else -> false
            }
            if (bound) return true
            child = current
            current = current.parent
        }
        return isNameBoundByInheritedMember(element, name, typesOnly = true)
    }

    /**
     * Tells whether [name] is already bound to something else at [element].
     *
     * Used before rewriting a declaration name into a scope where the new name may already be
     * taken. Unlike [hasConflictingReference], which asks what an *import* could capture, this
     * asks what the rewritten *text* would resolve to: locals, parameters, members, file-level
     * declarations, import bindings, and inherited members all take part.
     *
     * @param element position whose rewritten text must keep its meaning
     * @param name new simple name the position is about to spell
     * @param ignoredImports imported FQNs that already bind the declaration being renamed, so
     * re-spelling [name] through them is not a capture
     * @param ignoredDeclarations declarations that are the intended target themselves; a package
     * change leaves their names in place, so they must not be reported as captures
     * @return `true` when another declaration would win over the intended target
     */
    fun isNameBoundInScope(
        element: KtElement,
        name: String,
        ignoredImports: Set<FqName> = emptySet(),
        ignoredDeclarations: Set<PsiElement> = emptySet(),
    ): Boolean {
        val file = element.containingKtFile
        if (file.importDirectives.any { directive -> directive.bindsName(name, ignoredImports) }) return true
        if (file.declarations.any { declaration ->
                declaration !in ignoredDeclarations && declaration.declaresName(name)
            }) return true
        // A wildcard or default import binds names without spelling any directive of this file, so
        // a classifier or callable it provides hides a same-named package segment or declaration
        // exactly like an explicit import does. The composite scope also contains the explicit
        // import this check deliberately ignores, so the ignore sets have to follow along.
        if (isNameBoundByWildcardImport(element, name, ignoredImports, ignoredDeclarations)) return true
        var child: PsiElement = element
        var current: PsiElement? = element.parent
        while (current != null) {
            val bound = when (current) {
                is KtBlockExpression -> current.statements
                    .takeWhile { statement -> statement.textRange.startOffset < child.textRange.startOffset }
                    .any { statement -> (statement as? KtDeclaration)?.declaresName(name) == true }
                is KtClassBody -> current.declarations.any { declaration -> declaration.declaresName(name) }
                // A non-property primary-constructor parameter is not a member, but initializers
                // and `init` blocks see it, so the rewritten text would call the parameter.
                is KtClass -> element.isInsideClassInitializerScope(current) &&
                    current.primaryConstructorParameters.any { parameter -> parameter.bindsName(name) }
                is KtFunction -> current.valueParameters.any { parameter -> parameter.bindsName(name) }
                // A lambda that declares no parameters receives the implicit `it` parameter, which
                // the rewritten text would then call. Its function literal is a `KtFunction` with an
                // empty parameter list, so the enclosing lambda expression carries the rule.
                is KtLambdaExpression ->
                    name == "it" && current.functionLiteral.valueParameters.isEmpty()
                is KtPropertyAccessor ->
                    current.parameterList?.parameters?.any { parameter -> parameter.bindsName(name) } == true
                is KtForExpression -> current.loopParameter?.bindsName(name) == true
                is KtCatchClause -> current.catchParameter?.bindsName(name) == true
                is KtWhenExpression -> current.subjectVariable?.name == name
                else -> false
            }
            if (bound) return true
            child = current
            current = current.parent
        }
        return isNameBoundByInheritedMember(element, name, typesOnly = false)
    }

    /**
     * Tells whether a declaration of the same package is visible under [name] in [file].
     *
     * Same-package declarations need no import, so a package qualifier can resolve to a
     * classifier or callable declared in another file of the caller's own package. Treating such
     * a name as a free package segment would silently change which declaration a rewritten
     * qualified reference calls.
     *
     * @param file file whose declared package is searched
     * @param name identifier to look for
     * @return `true` when a declaration of the same package introduces that name
     */
    fun isDeclaredInPackage(file: KtFile, name: String): Boolean = runCatching {
        analyze(file) {
            val scope = findPackage(file.packageFqName)?.packageScope ?: return@analyze false
            scope.declarations.any { declaration -> declaration.declaredName() == name }
        }
    }.getOrDefault(false)

    /**
     * Tells whether the package a qualified reference points into declares [name].
     *
     * A qualified selector keeps resolving through its receiver, so a lexical binding of the new
     * name cannot capture it. A package receiver is different: the rewritten selector is looked up
     * in that package's member scope, where a sibling declaration of the new name takes part in
     * overload resolution and can silently replace the referenced declaration.
     *
     * @param expression qualified reference about to be rewritten
     * @param name identifier the rewritten selector will spell
     * @param classifier `true` for a type position, `false` for a value position
     * @return `true` when the referenced package declares a conflicting declaration
     */
    fun isDeclaredInQualifiedPackage(
        expression: KtSimpleNameExpression,
        name: String,
        classifier: Boolean,
    ): Boolean = runCatching {
        analyze(expression) {
            val packageFqName = expression.qualifiedPackageFqName() ?: return@analyze false
            val scope = findPackage(packageFqName)?.packageScope ?: return@analyze false
            if (classifier) {
                scope.classifiers.any { symbol -> symbol.name?.asString() == name }
            } else {
                scope.callables(Name.identifier(name)).any()
            }
        }
    }.getOrDefault(false)

    /**
     * Resolves the package this qualified reference is resolved in, or `null` for a value receiver.
     *
     * A dot-qualified call resolves its receiver symbol first; the source spelling is used as a
     * fallback because K2 does not resolve every package segment. A qualified type spells its
     * qualifier as a nested [KtUserType] instead.
     *
     * @return FQN of the package named by the qualifier, or `null` when it is not a package
     */
    context(_: KaSession)
    private fun KtSimpleNameExpression.qualifiedPackageFqName(): FqName? {
        qualifiedAccess()?.let { qualified ->
            val receiver = qualified.receiverExpression
            val symbol = receiver.dottedSelectorReference()?.resolveToSymbol()
            val fromSymbol = (symbol as? KaPackageSymbol)?.fqName
            if (fromSymbol != null) return fromSymbol
            return receiver.text.asPackageFqName()
        }
        val userType = parent as? KtUserType ?: return null
        if (userType.referenceExpression != this) return null
        return userType.qualifier?.text?.asPackageFqName()
    }

    /**
     * Returns the reference whose resolution names the last segment of this qualifier chain.
     *
     * @return the selector reference of a dot access, the name reference itself, or `null`
     */
    private fun KtExpression.dottedSelectorReference(): KtReference? = when (this) {
        is KtDotQualifiedExpression -> selectorExpression?.references
            ?.filterIsInstance<KtReference>()?.firstOrNull()
        is KtSimpleNameExpression -> references.filterIsInstance<KtReference>().firstOrNull()
        else -> null
    }

    /**
     * Converts a dotted source spelling to a package FQN when that package exists.
     *
     * @return the package FQN, or `null` when the text is not a dotted identifier chain or the
     *         package is unknown
     */
    context(_: KaSession)
    private fun String.asPackageFqName(): FqName? {
        val segments = split('.')
        if (segments.isEmpty() || !segments.all { segment -> Name.isValidIdentifier(segment) }) return null
        val fqName = FqName(this)
        return fqName.takeIf { packageName -> findPackage(packageName) != null }
    }

    /**
     * Tells whether a wildcard or default import of [file] binds [name] at [element].
     *
     * `import lib.*` and Kotlin's default imports introduce declarations without listing them in
     * the file's own directives. Scope resolution is therefore consulted instead of directive
     * text: the position's composite scope already knows which star imports apply and which
     * declarations they contribute.
     *
     * The composite scope also exposes the explicit imports of the file, so [ignoredImports] and
     * [ignoredDeclarations] are filtered out here: a binding the caller deliberately excludes —
     * the moved declaration's own import, or the moved declaration itself — is not a competing
     * declaration even though scope resolution reports it.
     *
     * @param element position whose scope is inspected
     * @param name identifier to look for
     * @param ignoredImports imported FQNs that already bind the declaration being renamed
     * @param ignoredDeclarations declarations that are the intended target themselves
     * @return `true` when a wildcard or default import provides a classifier or callable of that name
     */
    private fun isNameBoundByWildcardImport(
        element: KtElement,
        name: String,
        ignoredImports: Set<FqName> = emptySet(),
        ignoredDeclarations: Set<PsiElement> = emptySet(),
    ): Boolean = runCatching {
        analyze(element) {
            val identifier = Name.identifier(name)
            element.containingKtFile.scopeContext(element).scopes.any { scopeWithKind ->
                scopeWithKind.scope.classifiers(identifier)
                    .any { symbol -> !symbol.isIgnoredBinding(ignoredImports, ignoredDeclarations) } ||
                    scopeWithKind.scope.callables(identifier)
                        .any { symbol -> !symbol.isIgnoredBinding(ignoredImports, ignoredDeclarations) }
            }
        }
    }.getOrDefault(false)

    /**
     * Tells whether this resolved symbol is one the caller deliberately excludes from a check.
     *
     * A scope reports every binding it holds, including the explicit import that is being
     * rewritten and the declaration being moved. The declaration is matched by PSI identity
     * (including a constructor's enclosing class), the import by the symbol's FQN.
     *
     * @param ignoredImports imported FQNs that already bind the declaration being renamed
     * @param ignoredDeclarations declarations that are the intended target themselves
     * @return `true` when the symbol is one of the excluded bindings
     */
    private fun KaSymbol.isIgnoredBinding(
        ignoredImports: Set<FqName>,
        ignoredDeclarations: Set<PsiElement>,
    ): Boolean {
        val element = psi
        if (element != null) {
            if (element in ignoredDeclarations) return true
            val enclosingClass = element.enclosingClassOrObject()
            if (enclosingClass != null && enclosingClass in ignoredDeclarations) return true
        }
        if (ignoredImports.isEmpty()) return false
        val fqName = when (this) {
            is KaConstructorSymbol -> containingClassId?.asSingleFqName()
            is KaClassLikeSymbol -> classId?.asSingleFqName()
            is KaCallableSymbol -> callableId?.asSingleFqName()
            else -> null
        }
        return fqName != null && fqName in ignoredImports
    }

    /**
     * Tells whether a member of an enclosing or implicit receiver binds [name].
     *
     * Only declarations physically present in the enclosing class bodies are visible through
     * PSI; a superclass member (`class Caller : Base()` calling an inherited `fun After()`) lives
     * in another file but is still in scope unqualified. An extension function's receiver
     * (`fun Receiver.make()`) and a receiver lambda contribute their members through implicit
     * receivers rather than PSI ancestors, so K2's scope tower is consulted as well.
     *
     * @param element position whose rewritten text must keep its meaning
     * @param name identifier to look for
     * @param typesOnly `true` to search the type namespace only, `false` to include values
     * @return `true` when an inherited member or implicit receiver member of that name is in scope
     */
    private fun isNameBoundByInheritedMember(
        element: KtElement,
        name: String,
        typesOnly: Boolean,
    ): Boolean = runCatching {
        analyze(element) {
            var current: PsiElement? = element.parent
            while (current != null && current !is KtFile) {
                if (current is KtClassOrObject) {
                    val scope = current.getClassOrObjectSymbol()?.memberScope
                    if (scope != null) {
                        val bound = if (typesOnly) {
                            scope.classifiers.any { classifier -> classifier.name?.asString() == name }
                        } else {
                            scope.declarations.any { declaration -> declaration.declaredName() == name }
                        }
                        if (bound) return@analyze true
                    }
                }
                current = current.parent
            }
            // Extension receivers and receiver lambdas are not PSI ancestors of the position, but
            // their members shadow top-level declarations exactly like those of an enclosing class.
            element.containingKtFile.scopeContext(element).implicitValues
                .filterIsInstance<KaScopeImplicitReceiverValue>()
                .any { receiver ->
                    val scope = receiver.type.expandedSymbol?.memberScope ?: return@any false
                    if (typesOnly) {
                        scope.classifiers.any { classifier -> classifier.name?.asString() == name }
                    } else {
                        scope.declarations.any { declaration -> declaration.declaredName() == name }
                    }
                }
        }
    }.getOrDefault(false)

    /**
     * Tells whether this import directive introduces [name] into its file.
     *
     * An alias binds only the alias; a plain directive binds the imported declaration's simple
     * name. Wildcard directives bind nothing by name and are skipped.
     *
     * @param name identifier the directive may bind
     * @param ignoredImports imported FQNs that are already the intended target
     * @return `true` when this directive is the visible source of [name]
     */
    private fun KtImportDirective.bindsName(name: String, ignoredImports: Set<FqName>): Boolean {
        if (isAllUnder) return false
        val imported = importedFqName
        if (imported != null && imported in ignoredImports) return false
        val alias = aliasName
        return if (alias != null) alias == name else imported?.shortName()?.asString() == name
    }

    /**
     * Tells whether this statement or member declaration binds [name].
     *
     * @param name identifier to look for
     * @return `true` when this declaration introduces that name in its scope
     */
    private fun KtDeclaration.declaresName(name: String): Boolean = when (this) {
        is KtProperty -> this.name == name
        is KtNamedFunction -> this.name == name
        is KtClassOrObject -> this.name == name
        is KtTypeAlias -> this.name == name
        is KtDestructuringDeclaration -> entries.any { entry -> entry.name == name }
        else -> false
    }

    /**
     * Tells whether this parameter binds [name], including its destructured entries.
     *
     * `{ (After, _) -> ... }` introduces `After` through the parameter's destructuring declaration
     * rather than the parameter's own name, so checking `getName` alone misses it.
     *
     * @param name identifier to look for
     * @return `true` when the parameter or one of its destructured entries introduces that name
     */
    private fun KtParameter.bindsName(name: String): Boolean =
        this.name == name ||
            destructuringDeclaration?.entries?.any { entry -> entry.name == name } == true

    /**
     * Tells whether this position sees the primary-constructor parameters of [classOrObject].
     *
     * Those parameters are in scope in property initializers, `init` blocks, and the constructor's
     * own delegation call. A member function or property accessor opens a new scope that no longer
     * sees them, and so does a nested classifier, so the walk stops there.
     *
     * @param classOrObject classifier whose primary constructor declares the parameters
     * @return `true` when the element sits in an initializer scope of that classifier
     */
    private fun PsiElement.isInsideClassInitializerScope(classOrObject: KtClassOrObject): Boolean {
        var current: PsiElement? = this
        while (current != null && current != classOrObject) {
            if (current is KtNamedFunction || current is KtPropertyAccessor || current is KtClassOrObject) {
                return false
            }
            current = current.parent
        }
        return current == classOrObject
    }

    /**
     * Tells whether this statement or member declaration binds [name] in the type namespace.
     *
     * @param name identifier to look for
     * @return `true` when this declaration introduces a classifier of that name
     */
    private fun KtDeclaration.declaresClassifierName(name: String): Boolean = when (this) {
        is KtClassOrObject -> this.name == name
        is KtTypeAlias -> this.name == name
        else -> false
    }

    /**
     * Returns the simple name this symbol declares, or `null` when it has none.
     *
     * A convention operator reference is spelled by its operator (`+`), so comparing the
     * reference text alone misses references whose resolved callable carries the imported name.
     *
     * @return the declared name, or `null` for anonymous declarations and constructors
     */
    private fun KaSymbol.declaredName(): String? = (this as? KaNamedSymbol)?.name?.asString()

    /**
     * Returns the package this symbol is declared in, or `null` for members and locals.
     *
     * @return the declaring package, or `null` when the symbol is not a top-level declaration
     */
    private fun KaSymbol.declaredPackage(): FqName? = when (this) {
        is KaConstructorSymbol -> containingClassId?.takeUnless { classId -> classId.isLocal }?.packageFqName
        is KaCallableSymbol -> callableId?.takeIf { callableId -> callableId.className == null }?.packageName
        is KaClassLikeSymbol -> classId?.takeUnless { classId -> classId.isLocal }?.packageFqName
        else -> (psi?.parent as? KtFile)?.packageFqName
    }

    /**
     * Tells whether this symbol is one of the declarations whose references the import retargets.
     *
     * A constructor call resolves to a constructor whose PSI belongs to the constructed class, so
     * the enclosing classifier is compared as well.
     *
     * @param excludedDeclarations PSI of the declarations the import is meant for
     * @return `true` when this symbol is one of them or a constructor of one
     */
    private fun KaSymbol.belongsTo(excludedDeclarations: Set<PsiElement>): Boolean {
        val element = psi ?: return false
        if (element in excludedDeclarations) return true
        val enclosingClass = element.enclosingClassOrObject()
        return enclosingClass != null && enclosingClass in excludedDeclarations
    }

    /**
     * Tells whether an explicit import could change how this symbol is referenced.
     *
     * Only top-level declarations compete with an import: locals, parameters, and members are
     * resolved lexically or through their receiver and keep binding to themselves.
     *
     * @return `true` for a top-level class, constructor, callable, or property
     */
    private fun KaSymbol.isTopLevelBinding(): Boolean = when (this) {
        is KaConstructorSymbol -> containingClassId?.let { classId -> !classId.isLocal } == true
        is KaCallableSymbol -> callableId?.let { callableId -> callableId.className == null } == true
        is KaClassLikeSymbol -> classId?.let { classId -> !classId.isLocal } == true
        is KaVariableSymbol -> psi is KtProperty && psi?.parent is KtFile
        else -> false
    }

    /**
     * Passes every symbol resolved by the references of this element to [action].
     *
     * Convention constructs (`n[0]`, `val (a) = box`, `for (x in box)`) expose their callable only
     * through the references of the whole element, and a multi-reference such as the for loop's
     * resolves to several symbols, so all of them are reported.
     *
     * @param action invoked once per resolved symbol
     */
    context(_: KaSession)
    private fun KtElement.forEachConventionSymbol(action: (KaSymbol) -> Unit) {
        references.filterIsInstance<KtReference>().forEach { reference ->
            runCatching { reference.resolveToSymbols() }.getOrNull()?.forEach(action)
        }
    }

    /**
     * Tells whether this convention construct would start calling a top-level [name] declaration.
     *
     * A convention call spells no name, so comparing reference text cannot see it; the resolved
     * callable carries the name instead. [isCapture] decides whether a resolved top-level
     * declaration of that name is a problem for the caller — a reference that already points at
     * an allowed declaration is not.
     *
     * @param name the name the import introduces or stops binding
     * @param isCapture predicate telling whether the resolved symbol would be captured
     * @param onCapture invoked when a capture is found
     */
    context(_: KaSession)
    private fun KtElement.capturesTopLevelName(
        name: String,
        isCapture: (KaSymbol) -> Boolean,
        onCapture: () -> Unit,
    ) {
        forEachConventionSymbol { symbol ->
            if (symbol.declaredName() != name) return@forEachConventionSymbol
            if (!symbol.isTopLevelBinding()) return@forEachConventionSymbol
            if (!isCapture(symbol)) return@forEachConventionSymbol
            onCapture()
        }
    }

    /**
     * Tells whether this symbol is a top-level declaration the rewritten import stops binding.
     *
     * A convention reference resolving to a declaration that stays in the original package loses
     * the overload the directive provided; one resolving to a moving declaration follows the
     * rewrite and keeps working.
     *
     * @param imported original FQN of the moved declaration
     * @param movingPaths paths of the files whose package changes
     * @return `true` when the symbol stays in the directive's original package
     */
    private fun KaSymbol.staysInOriginalPackage(imported: FqName, movingPaths: Set<String>): Boolean {
        val path = psi?.containingFile?.virtualFile?.path
        if (path != null && path in movingPaths) return false
        return declaredPackage() == imported.parent()
    }

    /**
     * Tells whether adding an import of [imported] to a moved file would capture this symbol.
     *
     * An explicit import outranks both the same-package declarations of the moved file's new
     * package and any wildcard or default import that currently provides the symbol. A declaration
     * already bound through [imported] itself keeps its target.
     *
     * @param imported FQN of the declaration that stays behind
     * @param movingPaths paths of the files whose package changes
     * @return `true` when the added import would rebind the reference
     */
    private fun KaSymbol.isCapturedByOutgoingImport(imported: FqName, movingPaths: Set<String>): Boolean {
        val path = psi?.containingFile?.virtualFile?.path
        if (path != null && path in movingPaths) return true
        return declaredPackage() != imported.parent()
    }

    /**
     * Tells whether [fqName] names a top-level extension callable.
     *
     * Only an extension can act as a convention operator, so an import of a class, property, or
     * member callable of the same name leaves convention references and delegated properties
     * untouched.
     *
     * @param fqName FQN the import binds
     * @return `true` when the FQN denotes a top-level extension callable
     */
    context(_: KaSession)
    private fun isExtensionCallable(fqName: FqName): Boolean = runCatching {
        findTopLevelCallables(fqName.parent(), fqName.shortName()).any { symbol ->
            symbol.isExtension && symbol.callableId?.asSingleFqName() == fqName
        }
    }.getOrDefault(false)

    /**
     * Returns the extension delegate operators a delegated property implicitly selects.
     *
     * A delegated property spells neither `provideDelegate` nor `getValue`/`setValue`; K2 does not
     * expose the implicit calls either, so the applicable extensions are looked up by name and
     * receiver applicability in the property's scope. `provideDelegate` runs first and its return
     * type is the type the accessors are selected on, so the provider itself is a candidate when
     * the import introduces a provider. A member operator always wins over extensions, so it
     * cannot be captured by an import and is not reported.
     *
     * @param name operator name the import introduces
     * @return applicable extension operators of that name, empty when the property does not use it
     */
    context(_: KaSession)
    private fun KtProperty.delegateAccessorCandidates(name: String): List<KaCallableSymbol> {
        val delegateType = runCatching { delegateExpression?.expressionType }.getOrNull() ?: return emptyList()
        if (name == "provideDelegate") {
            // The provider is selected on the delegate expression's own type, before any accessor.
            return if (delegateType.memberDelegateOperator(name) != null) {
                emptyList()
            } else {
                applicableOperators(delegateType, name)
            }
        }
        val accessorNames = if (isVar) listOf("getValue", "setValue") else listOf("getValue")
        if (name !in accessorNames) return emptyList()
        val provider = delegateOperator(delegateType, "provideDelegate")
        val accessorTypes = provider
            ?.let { symbol -> runCatching { symbol.returnType }.getOrNull() }
            ?.let { providedType -> listOf(providedType) }
            ?: listOf(delegateType)
        return accessorTypes.flatMap { accessorType ->
            if (accessorType.memberDelegateOperator(name) != null) {
                emptyList()
            } else {
                applicableOperators(accessorType, name)
            }
        }
    }

    /**
     * Returns the top-level `set` extensions a compound indexed write implicitly selects.
     *
     * `sub[0]++` and `sub[0] += 1` spell only the getter, so K2 does not expose the setter half.
     * The applicable `set` extensions are looked up by name and receiver applicability at the
     * access site, mirroring the delegate accessor lookup. A member setter always wins over
     * extensions and cannot be captured by an import.
     *
     * @return applicable `set` extensions, empty for a plain read or a member setter
     */
    context(_: KaSession)
    private fun KtArrayAccessExpression.implicitSetterCandidates(): List<KaCallableSymbol> {
        if (!isCompoundWrite()) return emptyList()
        val receiverType = runCatching { arrayExpression?.expressionType }.getOrNull() ?: return emptyList()
        if (receiverType.memberIndexedSetter() != null) return emptyList()
        return applicableOperators(receiverType, "set")
    }

    /**
     * Tells whether this indexed access also writes its element.
     *
     * K2 exposes only the `get` convention of an indexed access; a compound assignment or
     * increment is what makes the compiler select a `set` as well.
     *
     * @return `true` when the access is assigned through rather than merely read
     */
    private fun KtArrayAccessExpression.isCompoundWrite(): Boolean {
        val parent = parent
        return when (parent) {
            is KtBinaryExpression -> parent.left == this &&
                parent.operationToken in KtTokens.ALL_ASSIGNMENTS && parent.operationToken != KtTokens.EQ
            is KtUnaryExpression -> parent.baseExpression == this &&
                parent.operationToken in setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)
            else -> false
        }
    }

    /**
     * Finds the operator of [name] the compiler would select on [type].
     *
     * A member declaration always outranks an extension, so it is returned first; otherwise the
     * first applicable extension visible at this property is used.
     *
     * @param type receiver type the operator is selected on
     * @param name operator name
     * @return the selected operator, or `null` when none applies
     */
    context(_: KaSession)
    private fun KtProperty.delegateOperator(type: KaType, name: String): KaCallableSymbol? =
        type.memberDelegateOperator(name) ?: applicableOperators(type, name).firstOrNull()

    /**
     * Returns the member delegate operator of [name] on this type, or `null` when it has none.
     *
     * Matching by name alone would treat an ordinary member whose signature does not fit the
     * delegation protocol as an accessor and hide the extension the property actually uses.
     *
     * @param name operator name
     * @return the applicable member operator, or `null` when the type declares none
     */
    context(_: KaSession)
    private fun KaType.memberDelegateOperator(name: String): KaCallableSymbol? = runCatching {
        expandedSymbol?.memberScope?.declarations
            ?.filterIsInstance<KaCallableSymbol>()
            ?.firstOrNull { symbol ->
                (symbol as? KaNamedSymbol)?.name?.asString() == name && symbol.isDelegateOperatorShape(name)
            }
    }.getOrNull()

    /**
     * Returns the member `set` operator of an indexed access on this type, or `null` when absent.
     *
     * A member setter takes the index and the assigned value; extensions declare the receiver
     * separately and therefore have one more value parameter.
     *
     * @return the member indexed setter, or `null` when there is none
     */
    context(_: KaSession)
    private fun KaType.memberIndexedSetter(): KaCallableSymbol? = runCatching {
        expandedSymbol?.memberScope?.declarations
            ?.filterIsInstance<KaCallableSymbol>()
            ?.firstOrNull { symbol ->
                (symbol as? KaNamedSymbol)?.name?.asString() == "set" &&
                    symbol is org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol &&
                    symbol.valueParameters.size == 2
            }
    }.getOrNull()

    /**
     * Returns the top-level extension operators of [name] applicable to [type] at this element.
     *
     * @param type receiver type the operator is selected on
     * @param name operator name
     * @return applicable extensions visible in the element's scope
     */
    context(_: KaSession)
    private fun KtElement.applicableOperators(type: KaType, name: String): List<KaCallableSymbol> = runCatching {
        val identifier = Name.identifier(name)
        containingKtFile.scopeContext(this).scopes.asSequence()
            .flatMap { scopeWithKind -> scopeWithKind.scope.callables(identifier) }
            .filter { symbol -> symbol.isExtension }
            .filter { symbol ->
                val receiverType = symbol.receiverParameter?.returnType ?: return@filter false
                type.isSubtypeOf(receiverType)
            }
            .distinct()
            .toList()
    }.getOrElse { emptyList() }

    /**
     * Returns the innermost class or object containing this element, or `null` at file level.
     *
     * @return the enclosing classifier, or `null` when this element is a top-level declaration
     */
    private fun PsiElement.enclosingClassOrObject(): KtClassOrObject? {
        var current: PsiElement? = this
        while (current != null && current !is KtFile) {
            if (current is KtClassOrObject) return current
            current = current.parent
        }
        return null
    }

    /**
     * Tells whether [expression] is the selector of a qualified access.
     *
     * Such a name keeps resolving through its receiver, so neither an import nor a rename can
     * rebind it to a lexical declaration of the same name.
     *
     * @param expression reference expression to inspect
     * @return `true` when the expression follows a dot or a type qualifier
     */
    fun isQualifiedReference(expression: KtSimpleNameExpression): Boolean = expression.isQualifiedSelector()

    /**
     * Tells whether this name is the selector of a qualified access.
     *
     * Only an unqualified name is rebound by an import; `receiver.name` keeps resolving through
     * its receiver whatever the file imports.
     *
     * @return `true` when this expression follows a dot or a type qualifier
     */
    private fun KtSimpleNameExpression.isQualifiedSelector(): Boolean {
        (parent as? KtCallExpression)
            ?.takeIf { call -> call.calleeExpression == this }
            ?.let { call ->
                (call.parent as? KtDotQualifiedExpression)?.let { qualified ->
                    return qualified.selectorExpression == call
                }
            }
        (parent as? KtDotQualifiedExpression)?.let { qualified ->
            return qualified.selectorExpression == this
        }
        return (parent as? KtUserType)?.let { userType ->
            userType.referenceExpression == this && userType.qualifier != null
        } == true
    }

    /**
     * Tells whether this selector is an extension called on a value rather than on a type.
     *
     * `1.choose()` and `box.choose()` bind the extension through extension-overload resolution,
     * where an explicit import of another same-named extension changes which one is chosen.
     * `Int.choose()` and `p.choose()` name a type or package as the receiver, so an import cannot
     * affect them — except for an extension on a companion object or a named object, which is
     * spelled with a class-like receiver while still being selected by overload resolution.
     *
     * @return `true` when an import of this name could change the selected extension
     */
    context(_: KaSession)
    private fun KtSimpleNameExpression.isValueReceiverExtension(): Boolean {
        val qualified = qualifiedAccess() ?: return false
        val symbol = references.filterIsInstance<KtReference>().firstOrNull()
            ?.resolveToSymbol() as? KaCallableSymbol ?: return false
        if (!symbol.isExtension) return false
        // `Box.choose(1)` spells the class-like receiver `Box`, but the extension declared on
        // `Box.Companion` is chosen by overload resolution, so an import can still rebind it.
        val receiverClass = symbol.receiverParameter?.returnType?.expandedSymbol
        if (receiverClass?.classKind == KaClassKind.COMPANION_OBJECT ||
            receiverClass?.classKind == KaClassKind.OBJECT
        ) {
            return true
        }
        val receiverRoot = qualified.receiverExpression.leadingReference() ?: return true
        val receiverSymbol = runCatching {
            receiverRoot.references.filterIsInstance<KtReference>().firstOrNull()?.resolveToSymbol()
        }.getOrNull()
        // A named object is a value even though K2 reports it as a class-like symbol: extensions
        // declared on the object's supertypes are selected by overload resolution for
        // `singleton.choose()`, so an import can still rebind the call. A class or package name in
        // the same position is a qualifier, which an import cannot affect.
        if (receiverSymbol is KaClassSymbol) return receiverSymbol.classKind == KaClassKind.OBJECT
        return receiverSymbol !is KaPackageSymbol
    }

    /**
     * Returns the qualified access this name selects, unwrapping a call's callee.
     *
     * @return the `.`-qualified expression whose selector this is, or `null` otherwise
     */
    private fun KtSimpleNameExpression.qualifiedAccess(): KtDotQualifiedExpression? {
        val selector = (parent as? KtCallExpression)?.takeIf { call -> call.calleeExpression == this } ?: this
        val qualified = selector.parent as? KtDotQualifiedExpression ?: return null
        return qualified.takeIf { expression -> expression.selectorExpression == selector }
    }

    /**
     * Returns the leftmost simple name of this receiver expression's qualified chain.
     *
     * @return the leftmost name, or `null` when the receiver is not a name-based chain
     */
    private fun KtElement.leadingReference(): KtSimpleNameExpression? = when (this) {
        is KtDotQualifiedExpression -> receiverExpression.leadingReference()
        is KtUserType -> qualifier?.leadingReference() ?: referenceExpression
        is KtSimpleNameExpression -> this
        else -> null
    }
}
