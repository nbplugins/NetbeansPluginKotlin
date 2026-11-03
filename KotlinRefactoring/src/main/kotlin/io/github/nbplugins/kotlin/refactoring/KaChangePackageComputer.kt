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
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.canBeCalledAsExtensionOn
import org.jetbrains.kotlin.analysis.api.components.expandedSymbol
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.memberScope
import org.jetbrains.kotlin.analysis.api.components.packageScope
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbol
import org.jetbrains.kotlin.analysis.api.components.resolveToSymbols
import org.jetbrains.kotlin.analysis.api.components.scopeContext
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.findPackage
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.MoveImport
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2ChangePackageDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2ChangePackageRefactoringProcessor
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtUserType

/**
 * Applies IDEA K2 Change Package semantics without its IDE-specific processor lifecycle.
 *
 * NetBeans supplies writable session PSI and persists the returned text atomically. The computer
 * retains the upstream algorithm's usage discovery, conflict pass, and package rewrite, then
 * retargets supported Kotlin references by rewriting captured source text. It deliberately leaves
 * filesystem mutations to no one: changing a package never changes a physical path.
 *
 * Source text is used for reference retargeting because mutating a package directive invalidates
 * every standalone PSI smart pointer, including those held by K2 [UsageInfo] objects. The IDEA
 * implementation survives this only because its documents are synchronised between the phases.
 *
 * @param ktFile representative writable K2-session source file
 */
class KaChangePackageComputer(
    private val ktFile: KtFile,
) {
    /** Describes the result of applying package changes to one or more Kotlin source files. */
    sealed class ApplyOutcome {
        /** K2 found blocking semantic conflicts and did not mutate any PSI. */
        data class Conflicts(val messages: List<String>) : ApplyOutcome()

        /** K2 produced final texts and pre-mutation snapshots for every changed participant. */
        data class Success(
            val changedFiles: Map<String, String>,
            val originalTexts: Map<String, String>,
        ) : ApplyOutcome()

        /** An unexpected K2 or PSI failure prevented the operation. */
        data class Error(val error: Throwable) : ApplyOutcome()
    }

    /**
     * Changes every source in [targets] to its requested package and retargets supported usages.
     *
     * @param targets writable source PSI mapped to their final declared packages
     * @param updateReferences whether supported external Kotlin references and direct imports update
     * @return conflicts without mutation, changed texts with original snapshots, or an error
     */
    fun apply(targets: Map<KtFile, FqName>, updateReferences: Boolean): ApplyOutcome {
        return try {
            require(targets.isNotEmpty()) { "Change Package requires at least one Kotlin file." }
            val files = targets.keys.toList()
            require(files.all { it.virtualFile?.path != null }) { "Change Package requires physical Kotlin files." }

            val processors = targets.entries.groupBy({ it.value }, { it.key }).map { (targetPackage, groupedFiles) ->
                K2ChangePackageRefactoringProcessor(
                    K2ChangePackageDescriptor(
                        project = ktFile.project,
                        files = groupedFiles.toSet(),
                        target = targetPackage,
                        searchForText = false,
                        searchInComments = false,
                    ),
                )
            }
            // Reuse the copied upstream Change Package processor for usage discovery, conflict
            // checking, and package rewriting; only its IDE-bound retargeting pass is replaced.
            val usages = if (updateReferences) processors.flatMap { it.findUsages().asIterable() }.distinct() else emptyList()
            val movedDeclarations = files.flatMap { file ->
                file.declarations.mapNotNull { declaration ->
                    (declaration as? KtNamedDeclaration)?.name?.let { name ->
                        file.packageFqName.child(Name.identifier(name)) to
                            targets.getValue(file).child(Name.identifier(name))
                    }
                }
            }.toMap()
            // Incoming changes fix callers that lose visibility of a moved declaration, either
            // because they shared its package or because they imported the whole old package.
            // Outgoing imports fix the moved file itself losing visibility of the declarations
            // that stay behind in its former package. Both run before any mutation and can add
            // conflicts of their own when a rewrite would rebind an unrelated name.
            val incoming = if (updateReferences) {
                findIncomingRetargeting(files, targets)
            } else {
                IncomingRetargeting()
            }
            // Outgoing imports are validated before anything is staged as well: an explicit
            // import outranks the same-package declarations the moved file gains, so it can
            // capture references to declarations moving along with that file.
            val outgoingImports = if (updateReferences) findOutgoingSamePackageImports(files, targets) else emptyMap()
            val conflicts = processors.flatMap { processor ->
                processor.findConflicts(usages.filterIsInstance<MoveRenameUsageInfo>()).values()
            } + incoming.conflicts + outgoingImportConflicts(outgoingImports, files, movedDeclarations.keys)
            if (conflicts.isNotEmpty()) return ApplyOutcome.Conflicts(conflicts.distinct())
            // Class-scoped wildcard imports (`import old.Outer.*`) follow the class only when the
            // prefix is a moved class; package wildcards never move with it.
            val movedClassFqNames = files.flatMap { file ->
                file.declarations.filterIsInstance<KtClassOrObject>().mapNotNull { declaration ->
                    declaration.name?.let { name -> file.packageFqName.child(Name.identifier(name)) }
                }
            }.toSet()
            val wildcardImportFiles = if (updateReferences) {
                KotlinMoveUsageSearchService.getInstance()
                    ?.findWildcardImportingFiles(ktFile.project, movedClassFqNames)
                    .orEmpty()
            } else {
                emptyList()
            }
            val movedPaths = files.mapNotNull { file -> file.virtualFile?.path }.toSet()
            // A caller whose only reference to the old package is an unused `import old.*` is
            // invisible to both usage and direct-import search. Once no declaration stays in the
            // package the directive becomes unresolved, so the importing file has to join the
            // participants and let the wildcard cleanup pass drop it.
            val vacatedWildcardImportFiles = if (updateReferences) {
                KotlinMoveUsageSearchService.getInstance()?.let { service ->
                    val vacatedPackages = files.map { file -> file.packageFqName }.toSet()
                        .filterNot { packageFqName ->
                            service.hasDeclarationsInPackage(ktFile.project, packageFqName, movedPaths)
                        }
                        .toSet()
                    if (vacatedPackages.isEmpty()) {
                        emptyList()
                    } else {
                        service.findPackageWildcardImportingFiles(ktFile.project, vacatedPackages)
                    }
                }.orEmpty()
            } else {
                emptyList()
            }
            val samePackageImportUpdates = (incoming.imports.keys + outgoingImports.keys).associateWith { file ->
                incoming.imports[file].orEmpty() + outgoingImports[file].orEmpty()
            }
            val importOnlyUsageFiles = if (updateReferences) {
                KotlinMoveUsageSearchService.getInstance()
                    ?.findImportingFiles(
                        ktFile.project,
                        movedDeclarations.mapKeys { (oldFqName, _) ->
                            MoveImport(oldFqName.parent(), oldFqName.shortName().asString())
                        }.mapValues { (_, newFqName) -> newFqName.parent() },
                    )
                    .orEmpty()
            } else {
                emptyList()
            }
            val participants = (files + filesWithUsages(usages) + importOnlyUsageFiles + wildcardImportFiles +
                vacatedWildcardImportFiles + samePackageImportUpdates.keys + incoming.edits.keys)
                .distinct()
            // Capture replacement ranges while PSI is still valid. Only import directives that
            // target a moved declaration and references that K2 resolved to one are retargeted, so
            // text that merely spells a moved FQN (comments, strings, unrelated chains) is untouched.
            val textEdits = if (updateReferences) {
                val importEdits = captureImportEdits(
                    participants,
                    movedDeclarations,
                    movedClassFqNames,
                    movedPaths,
                )
                (importEdits.keys + incoming.edits.keys).associateWith { file ->
                    importEdits[file].orEmpty() + incoming.edits[file].orEmpty()
                }
            } else {
                emptyMap()
            }
            // Header facts for import insertion come from PSI as well: an import-looking line
            // inside a raw string or block comment must neither count as an existing import nor
            // serve as the boundary where new imports are placed.
            val importInsertions = participants.mapNotNull { file ->
                val path = file.virtualFile?.path ?: return@mapNotNull null
                val insertion = importInsertionFor(file) ?: return@mapNotNull null
                path to insertion
            }.toMap()
            // Offsets before the package directive end are unaffected by its replacement length;
            // file annotations are the one place where Kotlin code precedes the directive.
            val packageDirectiveRanges = participants.mapNotNull { file ->
                val path = file.virtualFile?.path ?: return@mapNotNull null
                val range = file.packageDirective?.textRange
                path to (range?.startOffset to range?.endOffset)
            }.toMap()
            // Snapshot before any mutation: updatePackageDirective invalidates standalone PSI.
            val originalTexts = participants.mapNotNull { file ->
                file.virtualFile?.path?.let { path -> path to file.text }
            }.toMap()

            // Everything needed from PSI is captured above. From here on only text is touched,
            // because a package-directive replacement invalidates standalone PSI stubs and every
            // K2 UsageInfo smart pointer the upstream retargeter would dereference.
            processors.forEach { processor -> processor.updatePackages() }

            val changedFiles = linkedMapOf<String, String>()
            participants.forEach { file ->
                val path = file.virtualFile?.path ?: return@forEach
                val originalText = originalTexts[path] ?: return@forEach
                val directiveRange = packageDirectiveRanges[path]
                val text = targets[file]?.let { target ->
                    ensurePackageDirectiveSeparator(file.text, target, directiveRange?.first ?: 0)
                } ?: file.text
                // Captured offsets refer to the original text; only the package directive changed,
                // so its length delta realigns every range that follows the directive.
                val shift = text.length - originalText.length
                val shiftAfter = if (targets[file] != null) directiveRange?.second else null
                val retargeted = applyTextEdits(text, textEdits[file].orEmpty(), shift, shiftAfter)
                changedFiles[path] = addSamePackageImports(
                    retargeted.text,
                    samePackageImportUpdates[file].orEmpty(),
                    importInsertions[path],
                    retargeted.edits,
                    shift,
                )
            }
            ApplyOutcome.Success(changedFiles, originalTexts)
        } catch (error: Throwable) {
            ApplyOutcome.Error(error)
        }
    }

    /** Caller-side text changes captured before the package directives mutate. */
    private data class IncomingRetargeting(
        val imports: Map<KtFile, Set<FqName>> = emptyMap(),
        val edits: Map<KtFile, List<TextEdit>> = emptyMap(),
        val conflicts: List<String> = emptyList(),
    )

    /**
     * Captures how callers must change so references to moved declarations keep resolving.
     *
     * A caller loses visibility in two ways: it formerly shared the declaration's package, or it
     * imported the whole old package with a wildcard. A direct import is rewritten elsewhere and
     * keeps the binding, and a caller that ends in the declaration's final package keeps the
     * implicit binding. Qualified references are rewritten in place instead.
     *
     * Every moved declaration is searched directly rather than through the processor's usages:
     * the processor only reports functions, properties, and classes, while moving a type alias
     * also loses implicit visibility and can be referenced by its fully qualified name.
     *
     * @param movedFiles Kotlin sources whose declared package changes
     * @param targets mapped files with their final declared packages
     * @return imports that callers still need and reference replacements captured from PSI
     */
    private fun findIncomingRetargeting(
        movedFiles: Collection<KtFile>,
        targets: Map<KtFile, FqName>,
    ): IncomingRetargeting {
        val movedDeclarations = movedFiles.flatMap { file ->
            file.declarations.filterIsInstance<KtNamedDeclaration>().mapNotNull { declaration ->
                declaration.name?.let { name ->
                    val unified = file.packageFqName.child(Name.identifier(name)) to
                        targets.getValue(file).child(Name.identifier(name))
                    declaration to unified
                }
            }
        }.toMap()
        if (movedDeclarations.isEmpty()) return IncomingRetargeting()
        val session = KotlinMoveUsageSearchService.getInstance() ?: return IncomingRetargeting()
        val imports = linkedMapOf<KtFile, MutableSet<FqName>>()
        val edits = linkedMapOf<KtFile, MutableSet<TextEdit>>()
        val conflicts = mutableListOf<String>()
        movedDeclarations.forEach { (declaration, unified) ->
            val (oldFqName, newFqName) = unified
            val declarationFile = declaration.containingKtFile
            val originalPackage = oldFqName.parent()
            session.findUsages(declaration).forEach { reference ->
                val element = reference.element as? KtElement ?: return@forEach
                val file = element.containingFile as? KtFile ?: return@forEach
                // A qualified chain rewritten to a full package path keeps resolving on its own.
                // A root-package target becomes a bare name, and a shadowed package segment is
                // rewritten to a bare name as well, so both still need an explicit import.
                val rewrite = captureQualifiedReference(element, declaration, oldFqName, newFqName, edits, conflicts)
                if (rewrite == QualifiedRewrite.REWRITTEN) return@forEach
                if (file.virtualFile?.path == declarationFile.virtualFile?.path) return@forEach
                val finalPackage = targets[file] ?: file.packageFqName
                if (finalPackage == newFqName.parent()) return@forEach
                // An aliased directive binds only its alias; the original name still needs an
                // explicit import, so only an unaliased import suppresses that update.
                val importsDirectly = file.importDirectives.any { directive ->
                    !directive.isAllUnder && directive.aliasName == null &&
                        directive.importedFqName == oldFqName
                }
                if (importsDirectly) return@forEach
                // Only an unqualified usage that loses its implicit or wildcard binding needs the
                // import; a usage already rewritten through its own qualifier does not.
                if (rewrite == QualifiedRewrite.NONE) {
                    val sharesOriginalPackage = file.packageFqName == originalPackage
                    val importsOriginalPackage = file.importDirectives.any { directive ->
                        directive.isAllUnder && directive.importedFqName == originalPackage
                    }
                    if (!sharesOriginalPackage && !importsOriginalPackage) return@forEach
                    // The rewritten alias directive keeps this usage bound, so it needs no
                    // unaliased import of its own.
                    val simpleName = element as? KtSimpleNameExpression
                    if (simpleName != null && simpleName.isAliasBound(oldFqName)) return@forEach
                }
                imports.getOrPut(file, ::linkedSetOf) += newFqName
            }
        }
        collectIncomingConventionImports(session, movedDeclarations, targets, imports)
        // An explicit import takes precedence over wildcard imports and same-package
        // declarations, so it may capture references that point elsewhere today.
        val movedPsi = movedDeclarations.keys
        val movedOldFqNames = movedDeclarations.values.map { (oldFqName, _) -> oldFqName }.toSet()
        imports.forEach { (file, planned) ->
            planned.forEach { newFqName ->
                val shortName = newFqName.shortName().asString()
                when {
                    file.hasConflictingImport(newFqName, movedOldFqNames) -> conflicts +=
                        "Cannot update ${file.name}: it already imports another declaration named $shortName."
                    hasConflictingBinding(file, shortName, movedPsi) -> conflicts +=
                        "Cannot update ${file.name}: $shortName already refers to another declaration there."
                }
            }
        }
        // A rewritten direct import follows the moved declaration, but the overload set it bound
        // stays behind: a caller that used one of the remaining overloads would silently switch to
        // the moved declaration. An explicit import outranks both a wildcard of the old package and
        // the caller's own package, so keeping those overloads visible does not preserve which one
        // a call selects — every rewritten import has to be checked.
        val movedPaths = movedFiles.mapNotNull { file -> file.virtualFile?.path }.toSet()
        val movedImports = movedDeclarations.values.associate { (oldFqName, newFqName) ->
            MoveImport(oldFqName.parent(), oldFqName.shortName().asString()) to newFqName.parent()
        }
        session.findImportingFiles(ktFile.project, movedImports).forEach { file ->
            file.importDirectives.forEach directives@{ directive ->
                val importedFqName = directive.importedFqName ?: return@directives
                if (directive.isAllUnder) return@directives
                if (importedFqName !in movedOldFqNames) return@directives
                // An aliased directive binds its alias, so its call sites have to be matched by
                // that name when the rewritten directive loses the overloads staying behind.
                val boundName = directive.aliasName ?: importedFqName.shortName().asString()
                if (KotlinImportBindingCheck.hasConflictingImportRewrite(
                        file, importedFqName, movedPaths, boundName, aliased = directive.aliasName != null,
                    )
                ) {
                    conflicts += "Cannot update ${file.name}: import ${importedFqName.asString()} " +
                        "also binds declarations that stay behind."
                }
            }
        }
        return IncomingRetargeting(
            imports,
            edits.mapValues { (_, fileEdits) -> fileEdits.toList() },
            conflicts,
        )
    }

    /**
     * Adds imports for callers whose reference to a moved declaration is a convention usage.
     *
     * Usage search resolves simple-name references only, but an indexed access (`n[0]`), an
     * implicit invocation (`(box)()`), a destructuring declaration, a loop, and a delegated
     * property all bind top-level operators without spelling their names. A caller that shared
     * the old package or wildcard-imported it loses those bindings when the operator moves, so
     * the operator has to be imported explicitly.
     *
     * Only files that can currently see one of the old packages are analyzed: an explicit import
     * of the moved declaration is retargeted with its directive instead, and a caller outside
     * that visibility could not have bound the operator in the first place.
     *
     * @param session service enumerating the files of the analysis session
     * @param movedDeclarations moved declarations with their old and new fully qualified names
     * @param targets files whose declared package changes mapped to their final packages
     * @param imports accumulator receiving the imports each caller needs
     */
    private fun collectIncomingConventionImports(
        session: KotlinMoveUsageSearchService,
        movedDeclarations: Map<KtNamedDeclaration, Pair<FqName, FqName>>,
        targets: Map<KtFile, FqName>,
        imports: MutableMap<KtFile, MutableSet<FqName>>,
    ) {
        val movedPackages = movedDeclarations.values.mapTo(linkedSetOf()) { (oldFqName, _) -> oldFqName.parent() }
        if (movedPackages.isEmpty()) return
        val finalPackages = targets.entries.mapNotNull { (file, packageFqName) ->
            file.virtualFile?.path?.let { path -> path to packageFqName }
        }.toMap()
        val moved = movedDeclarations.keys
        session.findSourceFiles(ktFile.project).forEach { file ->
            val visiblePackages = movedPackages.filter { packageFqName ->
                packageFqName == file.packageFqName || file.importDirectives.any { directive ->
                    directive.isAllUnder && directive.importedFqName == packageFqName
                }
            }
            if (visiblePackages.isEmpty()) return@forEach
            val used = runCatching { analyze(file) { file.movedConventionUsages(visiblePackages, moved) } }
                .getOrDefault(emptySet())
            used.forEach { declaration ->
                val (oldFqName, newFqName) = movedDeclarations.getValue(declaration)
                if (!file.needsIncomingImport(declaration, oldFqName, newFqName, finalPackages)) return@forEach
                imports.getOrPut(file, ::linkedSetOf) += newFqName
            }
        }
    }

    /**
     * Collects moved declarations this file uses through conventions that spell no name.
     *
     * An operator can only have been bound through a visible old package, so the delegate
     * lookups search the caller's own scope. The setter half of a compound indexed write is not
     * exposed by K2 either; it is selected on the receiver type and skipped when a member setter
     * wins.
     *
     * @param visiblePackages old packages this file can currently see
     * @param moved declarations whose move the operation performs
     * @return moved declarations this file selects implicitly
     */
    @OptIn(KaExperimentalApi::class)
    context(_: KaSession)
    private fun KtFile.movedConventionUsages(
        visiblePackages: List<FqName>,
        moved: Set<KtNamedDeclaration>,
    ): Set<KtNamedDeclaration> {
        val found = linkedSetOf<KtNamedDeclaration>()
        fun record(symbol: KaSymbol?) {
            val declaration = symbol?.psi as? KtNamedDeclaration ?: return
            if (declaration in moved) found += declaration
        }
        fun movedOperator(type: KaType, name: String, arguments: List<KaType?>? = null): KaCallableSymbol? {
            val candidates = visiblePackages.asSequence()
                .mapNotNull { packageFqName -> findPackage(packageFqName)?.packageScope }
                .flatMap { scope -> scope.declarations.asSequence() }
                .filterIsInstance<KaCallableSymbol>()
                .filter { symbol -> (symbol as? KaNamedSymbol)?.name?.asString() == name && symbol.isExtension }
                .toList()
            // Both `Box.set` and the more specific `SubBox.set` can be visible at once; only the
            // one overload resolution selects may be recorded, or the caller would import a
            // setter it does not call.
            return selectExtension(candidates, type, arguments)
        }
        accept(object : KtTreeVisitorVoid() {
            override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                super.visitArrayAccessExpression(expression)
                expression.conventionSymbols().forEach(::record)
                if (!expression.isCompoundWrite()) return
                val receiverType = expression.arrayExpression?.expressionType ?: return
                // A member setter wins only when it accepts the actual indices and value. A
                // misfitting overload (`set(String, Int)` for `box[0]++`) leaves the write to
                // the applicable extension, which the caller then has to import.
                val arguments = expression.setterArgumentTypes()
                if (!receiverType.hasApplicableMemberIndexedSetter(arguments)) {
                    record(movedOperator(receiverType, "set", arguments))
                }
            }

            // The implicit invocation reference belongs to the call expression, not to the
            // spelled callee, so `(box)()` is collected here for simple-name callees too.
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                expression.conventionSymbols().forEach(::record)
            }

            override fun visitDestructuringDeclarationEntry(multiDeclarationEntry: KtDestructuringDeclarationEntry) {
                super.visitDestructuringDeclarationEntry(multiDeclarationEntry)
                multiDeclarationEntry.conventionSymbols().forEach(::record)
            }

            override fun visitForExpression(expression: KtForExpression) {
                super.visitForExpression(expression)
                expression.conventionSymbols().forEach(::record)
            }

            override fun visitProperty(property: KtProperty) {
                super.visitProperty(property)
                property.movedDelegateOperators(moved).forEach { declaration -> found += declaration }
            }
        })
        return found
    }

    /**
     * Returns the symbols the convention references of this element resolve to.
     *
     * @return every resolved symbol, including the operator a convention invokes
     */
    context(_: KaSession)
    private fun KtElement.conventionSymbols(): List<KaSymbol> =
        references.filterIsInstance<KtReference>()
            .flatMap { reference -> runCatching { reference.resolveToSymbols() }.getOrNull().orEmpty() }

    /**
     * Collects the moved delegate operators this property selects implicitly.
     *
     * `provideDelegate` runs first and its return type decides which accessor is selected, so the
     * provider and both accessors of a `var` are looked up. A member operator always wins over
     * an extension and cannot be captured by an import, so the member is checked first.
     *
     * @param moved declarations whose move the operation performs
     * @return moved operators this property selects
     */
    @OptIn(KaExperimentalApi::class)
    context(_: KaSession)
    private fun KtProperty.movedDelegateOperators(moved: Set<KtNamedDeclaration>): Set<KtNamedDeclaration> {
        val delegateType = runCatching { delegateExpression?.expressionType }.getOrNull() ?: return emptySet()
        val operators = linkedSetOf<KtNamedDeclaration>()
        fun record(symbol: KaCallableSymbol?) {
            val declaration = symbol?.psi as? KtNamedDeclaration ?: return
            if (declaration in moved) operators += declaration
        }
        fun selectedOperator(type: KaType, name: String): KaCallableSymbol? =
            type.memberDelegateOperator(name) ?: scopeDelegateOperator(type, name)
        val provider = selectedOperator(delegateType, "provideDelegate")
        record(provider)
        // The provider's return type is declared in terms of its own type parameters; only the
        // instantiation inferred from the delegate receiver selects the real accessor.
        val accessorType = provider?.returnTypeInferredFrom(delegateType) ?: delegateType
        listOf("getValue", "setValue").forEach { name ->
            if (name == "setValue" && !isVar) return@forEach
            record(selectedOperator(accessorType, name))
        }
        return operators
    }

    /**
     * Tells whether [file] loses its implicit or wildcard binding to a moved declaration.
     *
     * A direct import is retargeted with its own directive, and a file ending in the declaration's
     * new package still sees it without an import. Everything else that shared the old package or
     * wildcard-imported it needs an explicit import once the declaration moves.
     *
     * @param declaration moved declaration the convention usage resolves to
     * @param oldFqName declaration's fully qualified name before the change
     * @param newFqName declaration's fully qualified name after the change
     * @param finalPackages paths of the moving files mapped to their final packages
     * @return `true` when the caller has to import [newFqName]
     */
    private fun KtFile.needsIncomingImport(
        declaration: KtNamedDeclaration,
        oldFqName: FqName,
        newFqName: FqName,
        finalPackages: Map<String, FqName>,
    ): Boolean {
        val path = virtualFile?.path
        if (path != null && path == declaration.containingKtFile.virtualFile?.path) return false
        val finalPackage = path?.let(finalPackages::get) ?: packageFqName
        if (finalPackage == newFqName.parent()) return false
        val importsDirectly = importDirectives.any { directive ->
            !directive.isAllUnder && directive.aliasName == null && directive.importedFqName == oldFqName
        }
        if (importsDirectly) return false
        if (packageFqName == oldFqName.parent()) return true
        return importDirectives.any { directive ->
            directive.isAllUnder && directive.importedFqName == oldFqName.parent()
        }
    }

    /**
     * Reports outgoing imports that cannot be added without changing an existing reference.
     *
     * An outgoing import keeps a moved file bound to a declaration left in its former package,
     * but an explicit import outranks the same-package declarations the file gains in its new
     * package. A second import of the same simple name is a conflicting import, so both cases
     * abort the change before any text is staged.
     *
     * @param outgoingImports moved files mapped to the imports their references now require
     * @param movedFiles Kotlin sources whose declared package changes
     * @param movedOldFqNames original FQNs of the declarations moving with those files
     * @return conflict messages for imports that cannot be added safely
     */
    private fun outgoingImportConflicts(
        outgoingImports: Map<KtFile, Set<FqName>>,
        movedFiles: Collection<KtFile>,
        movedOldFqNames: Set<FqName>,
    ): List<String> {
        val movedPaths = movedFiles.mapNotNull { file -> file.virtualFile?.path }.toSet()
        return outgoingImports.flatMap { (file, imports) ->
            imports.mapNotNull { importFqName ->
                val name = importFqName.shortName().asString()
                when {
                    file.hasConflictingImport(importFqName, movedOldFqNames) ->
                        "Cannot update ${file.name}: it already imports another declaration named $name."
                    KotlinImportBindingCheck.hasConflictingOutgoingImport(file, importFqName, movedPaths) ->
                        "Cannot update ${file.name}: importing $name would capture a reference " +
                            "to a declaration that moves with the file."
                    else -> null
                }
            }
        }
    }

    /**
     * Tells whether [file] already imports a different declaration under the name [imported] binds.
     *
     * Adding a second explicit import with the same simple name is a conflicting import, and one
     * naming a moved declaration's old path is retargeted instead of being a conflict.
     *
     * @param imported declaration the file is about to import
     * @param movedOldFqNames original FQNs of the moved declarations
     * @return `true` when an existing unaliased import binds the same simple name elsewhere
     */
    private fun KtFile.hasConflictingImport(imported: FqName, movedOldFqNames: Set<FqName>): Boolean =
        importDirectives.any { directive ->
            val importedFqName = directive.importedFqName
            !directive.isAllUnder && directive.aliasName == null && importedFqName != null &&
                importedFqName != imported && importedFqName !in movedOldFqNames &&
                importedFqName.shortName() == imported.shortName()
        }

    /**
     * Tells whether an explicit import of [name] would capture another declaration in [file].
     *
     * An explicit import takes precedence over a wildcard import and over same-package
     * declarations, so inserting one for the moved declaration can silently rebind an existing
     * unqualified reference — an unrelated overload, a same-package function, or a classifier
     * from another package. References that already resolve to a moved declaration are the ones
     * the import is meant for and are ignored.
     *
     * @param file caller that is about to receive the import
     * @param name simple name the import binds
     * @param movedDeclarations PSI of the declarations whose references the import retargets
     * @return `true` when some unqualified reference in [file] would change its target
     */
    private fun hasConflictingBinding(
        file: KtFile,
        name: String,
        movedDeclarations: Set<KtNamedDeclaration>,
    ): Boolean = KotlinImportBindingCheck.hasConflictingReference(file, name, movedDeclarations)

    /** What a captured qualified chain needs after its replacement. */
    private enum class QualifiedRewrite {
        /** The reference does not spell the old FQN, so it keeps its text and its import. */
        NONE,

        /** The chain was replaced by the new fully qualified name and resolves on its own. */
        REWRITTEN,

        /** The chain was replaced by a bare name, so the caller now needs an explicit import. */
        NEEDS_IMPORT,
    }

    /**
     * Records a replacement when [element] spells a moved declaration by its old fully qualified name.
     *
     * Simple-name references keep their text and follow their rewritten import; only a qualified
     * chain that equals the old FQN needs replacing in place. The chain text alone is not enough:
     * an extension receiver can spell the old package name (`a.decorate()` on a local `a: String`
     * bound to `a.decorate`), and replacing that chain would drop the receiver. The chain's
     * leftmost name must therefore not resolve to a non-package symbol.
     *
     * The new package's first segment can itself be captured by a local binding
     * (`fun use(dest: Holder) = old.moved()`), in which case the replacement would call a member
     * instead of the moved declaration. The chain is then reduced to the bare name and an import
     * is requested; when that name is shadowed as well, the caller reports a conflict instead.
     *
     * @param element reference expression that K2 resolved to the moved declaration
     * @param movedDeclaration declaration being moved; a package change keeps its name, so it
     * must not be mistaken for a capture of the shortened reference
     * @param oldFqName declaration FQN before the package change
     * @param newFqName declaration FQN after the package change
     * @param edits per-file replacement accumulator
     * @param conflicts accumulator for changes that cannot be expressed safely
     * @return what the caller still needs to do for this reference
     */
    private fun captureQualifiedReference(
        element: KtElement,
        movedDeclaration: KtNamedDeclaration,
        oldFqName: FqName,
        newFqName: FqName,
        edits: MutableMap<KtFile, MutableSet<TextEdit>>,
        conflicts: MutableList<String>,
    ): QualifiedRewrite {
        // A root-package FQN is a bare name. Qualifying such a reference would rewrite
        // declaration-like positions that cannot take a package prefix (`::moved`); imports
        // alone keep root-package bindings intact.
        if (oldFqName.parent().isRoot) return QualifiedRewrite.NONE
        val file = element.containingFile as? KtFile ?: return QualifiedRewrite.NONE
        val chain = element.qualifiedChain() ?: return QualifiedRewrite.NONE
        if (!chain.matches(oldFqName)) return QualifiedRewrite.NONE
        // K2 resolves package references to `null` in this position, so only a concrete
        // non-package symbol proves the chain root is a value/class rather than a package.
        val root = chain.rootReference
        if (root != null) {
            val rootSymbol = runCatching {
                analyze(file) { root.mainReference?.resolveToSymbol() }
            }.getOrNull()
            if (rootSymbol != null && rootSymbol !is KaPackageSymbol) return QualifiedRewrite.NONE
        }
        // A root target has no receiver to rewrite: `old.moved()` becomes the bare `moved`.
        if (newFqName.parent().isRoot) {
            val shortName = newFqName.shortName().asString()
            // A member or local of that name outranks an import, so the shortened call would bind
            // to it instead; the chain cannot stay either, since the old package disappears.
            if (element.isNameShadowedInScope(
                    shortName,
                    ignoredImports = setOf(oldFqName),
                    ignoredDeclarations = setOf(movedDeclaration),
                )
            ) {
                conflicts += "Cannot update ${file.name}: $shortName already refers to another " +
                    "declaration there."
                return QualifiedRewrite.NONE
            }
            edits.getOrPut(file, ::linkedSetOf) += TextEdit(
                chain.startOffset,
                chain.endOffset,
                KotlinSourceNames.renderFqName(newFqName),
            )
            return QualifiedRewrite.NEEDS_IMPORT
        }
        val packageHead = newFqName.parent().pathSegments().first().asString()
        // The package segment is resolved as an ordinary name at this position, so a local,
        // parameter, member, inherited member, or import of the caller can hide the package.
        // A sibling file's `object dest` is visible through the package as well.
        if (file.hasDeclarationNamed(packageHead) ||
            KotlinImportBindingCheck.isNameBoundInScope(
                element,
                packageHead,
                ignoredDeclarations = setOf(movedDeclaration),
            ) ||
            KotlinImportBindingCheck.isDeclaredInPackage(file, packageHead)
        ) {
            // The new package's first segment would resolve to a local declaration instead of
            // the package, so the chain is shortened and the declaration imported instead.
            val shortName = newFqName.shortName().asString()
            if (element.isNameShadowedInScope(
                    shortName,
                    ignoredImports = setOf(oldFqName),
                    ignoredDeclarations = setOf(movedDeclaration),
                )
            ) {
                conflicts += "Cannot update ${file.name}: both $packageHead and $shortName refer " +
                    "to other declarations there."
                return QualifiedRewrite.NONE
            }
            edits.getOrPut(file, ::linkedSetOf) += TextEdit(
                chain.startOffset,
                chain.endOffset,
                KotlinSourceNames.renderIdentifier(shortName),
            )
            return QualifiedRewrite.NEEDS_IMPORT
        }
        edits.getOrPut(file, ::linkedSetOf) += TextEdit(
            chain.startOffset,
            chain.endOffset,
            KotlinSourceNames.renderFqName(newFqName),
        )
        return QualifiedRewrite.REWRITTEN
    }

    /**
     * Tells whether [name] is bound anywhere in this file.
     *
     * A package qualifier is resolved as an ordinary name in scope, so a property, parameter,
     * local function, classifier, import alias, or imported declaration with the same name can
     * hide the package. The scan is deliberately file-wide: missing a binding would silently
     * change which declaration is called, while an unrelated match only costs a fallback to an
     * explicit import.
     *
     * @param name identifier to look for
     * @return `true` when the file declares, imports, or aliases that name at any level
     */
    private fun KtFile.hasDeclarationNamed(name: String): Boolean {
        var found = false
        accept(object : KtTreeVisitorVoid() {
            override fun visitProperty(property: KtProperty) {
                if (property.name == name) found = true
                super.visitProperty(property)
            }

            override fun visitParameter(parameter: KtParameter) {
                if (parameter.name == name) found = true
                super.visitParameter(parameter)
            }

            override fun visitDestructuringDeclarationEntry(multiDeclarationEntry: KtDestructuringDeclarationEntry) {
                if (multiDeclarationEntry.name == name) found = true
                super.visitDestructuringDeclarationEntry(multiDeclarationEntry)
            }

            override fun visitNamedFunction(function: KtNamedFunction) {
                if (function.name == name) found = true
                super.visitNamedFunction(function)
            }

            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                if (classOrObject.name == name) found = true
                super.visitClassOrObject(classOrObject)
            }

            override fun visitTypeAlias(typeAlias: KtTypeAlias) {
                if (typeAlias.name == name) found = true
                super.visitTypeAlias(typeAlias)
            }

            override fun visitImportDirective(importDirective: KtImportDirective) {
                // A plain import binds the imported declaration's simple name, so `import other.dest`
                // hides a package `dest` exactly like an alias does.
                val boundName = importDirective.aliasName
                    ?: importDirective.importedFqName
                        ?.takeUnless { importDirective.isAllUnder }
                        ?.shortName()
                        ?.asString()
                if (boundName == name) found = true
                super.visitImportDirective(importDirective)
            }
        })
        return found
    }

    /**
     * Tells whether [name] is bound to a local or member declaration visible at this element.
     *
     * @param name identifier to look for in the enclosing scopes
     * @param ignoredImports imported FQNs that bind the moved declaration itself
     * @param ignoredDeclarations declarations that are the moved target themselves
     * @return `true` when a value, function, or nested declaration here would win over an import
     */
    private fun KtElement.isNameShadowedInScope(
        name: String,
        ignoredImports: Set<FqName> = emptySet(),
        ignoredDeclarations: Set<PsiElement> = emptySet(),
    ): Boolean = KotlinImportBindingCheck.isNameBoundInScope(
        this,
        name,
        ignoredImports,
        ignoredDeclarations,
    )

    /**
     * Finds declarations that stay in a moved file's former package and need an explicit import.
     *
     * Changing a package directive removes the implicit same-package visibility that such
     * references rely on, and those references have no import directive to rewrite. Resolve the
     * moved file's references before any directive is mutated and record the imports they need.
     *
     * Members reached through a receiver are skipped: only top-level declarations participate in
     * same-package visibility, and a receiver whose text already spells out the package makes the
     * reference self-sufficient.
     *
     * @param movedFiles Kotlin sources whose declared package changes
     * @param targets mapped files with their final declared packages
     * @return affected files mapped to the imports their references now require
     */
    private fun findOutgoingSamePackageImports(
        movedFiles: Collection<KtFile>,
        targets: Map<KtFile, FqName>,
    ): Map<KtFile, Set<FqName>> {
        val movedPaths = movedFiles.mapNotNull { file -> file.virtualFile?.path }.toSet()
        val updates = linkedMapOf<KtFile, Set<FqName>>()
        movedFiles.forEach { file ->
            val originalPackage = file.packageFqName
            if (targets[file] == originalPackage) return@forEach
            // An aliased import binds only the alias, so it cannot satisfy a simple-name use of
            // the original name; only an unaliased import counts as an existing binding.
            val importedFqNames = file.importDirectives
                .filterNot { directive -> directive.isAllUnder || directive.aliasName != null }
                .mapNotNull { it.importedFqName }
                .toSet()
            val starPackages = file.importDirectives
                .filter { it.isAllUnder }
                .mapNotNull { it.importedFqName }
                .toSet()
            val destination = targets.getValue(file)
            // A same-package declaration outranks a wildcard or default import, so a reference
            // that is bound through one of those has to become an explicit import when the
            // destination package declares the same name.
            val destinationNames = runCatching {
                analyze(file) { destinationDeclarationNames(destination, movedFiles, targets) }
            }.getOrDefault(emptySet())
            val missingImports = linkedSetOf<FqName>()
            runCatching {
                analyze(file) {
                    file.accept(object : KtTreeVisitorVoid() {
                        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                            super.visitSimpleNameExpression(expression)
                            if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                            val reference = expression.references.filterIsInstance<KtReference>().firstOrNull() ?: return
                            val symbol = reference.resolveToSymbol() ?: return
                            val referencedName = expression.getReferencedName()
                            file.shadowedWildcardImport(
                                expression,
                                symbol,
                                destination,
                                destinationNames,
                                importedFqNames,
                                movedPaths,
                            )?.let { importFqName -> missingImports += importFqName }
                            val topLevelFqName = samePackageTopLevelName(symbol, originalPackage, movedPaths)
                            // A call may spell a same-package type alias rather than the class it
                            // expands to: K2 can report the constructor of the underlying class,
                            // but the source keeps calling the alias, so the alias itself is
                            // imported. Convention operators spell `+` while resolving to `plus`,
                            // and an import of the spelled symbol would be unresolved; only a
                            // spelled name that resolves to a type alias of the original package
                            // may be imported, never one synthesized from a class reference.
                            val importFqName = if (topLevelFqName != null) {
                                // The reference may already be bound through an import alias of
                                // the resolved declaration; the alias directive stays valid.
                                if (expression.isAliasBound(topLevelFqName)) return
                                if (referencedName == topLevelFqName.shortName().asString()) {
                                    topLevelFqName
                                } else if (symbol is KaConstructorSymbol || symbol is KaClassLikeSymbol) {
                                    // A differently spelled class reference denotes a type alias;
                                    // only an alias declared in the original package can be
                                    // imported by that name.
                                    samePackageTypeAliasName(expression, symbol, originalPackage, movedPaths)
                                        ?: return
                                } else {
                                    // A convention operator spells `+` while resolving to `plus`;
                                    // the resolved callable id is the only usable spelling.
                                    topLevelFqName
                                }
                            } else {
                                samePackageTypeAliasName(expression, symbol, originalPackage, movedPaths)
                                    ?: return
                            }
                            if (importFqName in importedFqNames || originalPackage in starPackages) return
                            // An extension access on a value receiver can spell the package name
                            // (`p.extra` on a parameter `p`, or on a top-level `object p`), so a
                            // resolved receiver keeps the access dependent on an import and
                            // bypasses the spelling test. K2 resolves package references to
                            // `null` in this position, so any concrete non-package symbol proves
                            // the chain root is a value rather than a package.
                            val receiverIsValue = expression.qualifiedReceiver()?.let { receiver ->
                                val symbol = runCatching {
                                    receiver.leadingReference()?.mainReference?.resolveToSymbol()
                                }.getOrNull()
                                symbol != null && symbol !is KaPackageSymbol
                            } == true
                            if (!receiverIsValue && expression.isPackageQualified(originalPackage)) return
                            missingImports += importFqName
                        }

                        // A call on a value spells no name for the convention it invokes:
                        // `(box)()` and `box()` both depend on a top-level `operator fun Box.invoke`
                        // that only the original package's visibility provided. The implicit
                        // invocation reference belongs to the call expression rather than to any
                        // spelled name, so it is collected here for simple-name callees too:
                        // `box` resolves to the parameter, never to the operator. Spelled
                        // declarations are still handled by visitSimpleNameExpression, and the
                        // accumulator deduplicates the overlap.
                        override fun visitCallExpression(expression: KtCallExpression) {
                            super.visitCallExpression(expression)
                            val symbols = expression.references.filterIsInstance<KtReference>()
                                .flatMap { reference ->
                                    runCatching { reference.resolveToSymbols() }.getOrNull().orEmpty()
                                }
                            // A destination declaration of the same name outranks the wildcard or
                            // default import that currently binds the operator.
                            symbols.forEach { symbol ->
                                file.shadowedDependency(
                                    symbol,
                                    destination,
                                    destinationNames,
                                    importedFqNames,
                                    movedPaths,
                                )?.let { importFqName -> missingImports += importFqName }
                            }
                            symbols.mapNotNull { symbol ->
                                samePackageTopLevelName(symbol, originalPackage, movedPaths)
                            }
                                .filter { importFqName ->
                                    importFqName !in importedFqNames && originalPackage !in starPackages
                                }
                                .forEach { importFqName -> missingImports += importFqName }
                        }

                        // A destructuring declaration spells no name for the operator it calls:
                        // `val (x) = box` depends on a top-level `operator fun Box.component1`
                        // that only the original package's visibility provided, so the convention
                        // reference has to be picked up separately. Its reference resolves to both
                        // the entry's own property and the convention function.
                        override fun visitDestructuringDeclarationEntry(
                            multiDeclarationEntry: KtDestructuringDeclarationEntry,
                        ) {
                            super.visitDestructuringDeclarationEntry(multiDeclarationEntry)
                            multiDeclarationEntry.references.filterIsInstance<KtReference>().forEach { reference ->
                                val symbols = runCatching { reference.resolveToSymbols() }.getOrNull()
                                    ?: return@forEach
                                symbols.forEach { symbol ->
                                    file.shadowedDependency(
                                        symbol,
                                        destination,
                                        destinationNames,
                                        importedFqNames,
                                        movedPaths,
                                    )?.let { importFqName -> missingImports += importFqName }
                                }
                                val importFqName = symbols.firstNotNullOfOrNull { symbol ->
                                    samePackageTopLevelName(symbol, originalPackage, movedPaths)
                                } ?: return@forEach
                                if (importFqName in importedFqNames || originalPackage in starPackages) {
                                    return@forEach
                                }
                                missingImports += importFqName
                            }
                        }

                        // A for loop spells no name for the conventions it calls: `for (x in box)`
                        // depends on top-level `operator fun Box.iterator`, `Cursor.hasNext`, and
                        // `Cursor.next` that only the original package's visibility provided. Its
                        // reference resolves to every one of them — and to the loop variable's
                        // own property — so all retained callables are collected.
                        override fun visitForExpression(expression: KtForExpression) {
                            super.visitForExpression(expression)
                            val symbols = expression.references
                                .filterIsInstance<KtReference>()
                                .flatMap { reference ->
                                    runCatching { reference.resolveToSymbols() }.getOrNull().orEmpty()
                                }
                            symbols.forEach { symbol ->
                                file.shadowedDependency(
                                    symbol,
                                    destination,
                                    destinationNames,
                                    importedFqNames,
                                    movedPaths,
                                )?.let { importFqName -> missingImports += importFqName }
                            }
                            symbols.mapNotNull { symbol ->
                                samePackageTopLevelName(symbol, originalPackage, movedPaths)
                            }
                                .filter { importFqName ->
                                    importFqName !in importedFqNames && originalPackage !in starPackages
                                }
                                .forEach { importFqName -> missingImports += importFqName }
                        }

                        // Convention operators are not spelled as simple names: `box[0]` depends
                        // on a top-level `operator fun Box.get` that same-package visibility
                        // provided, so its reference has to be captured separately. A compound
                        // indexed write (`box[0]++`, `box[0] += 1`) exposes only `get`; its
                        // setter half is resolved implicitly and is looked up separately below.
                        override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                            super.visitArrayAccessExpression(expression)
                            val resolvedOperators = expression.references
                                .filterIsInstance<KtReference>()
                                .flatMap { reference ->
                                    runCatching { reference.resolveToSymbols() }.getOrNull().orEmpty()
                                }
                            resolvedOperators.forEach { symbol ->
                                file.shadowedDependency(
                                    symbol,
                                    destination,
                                    destinationNames,
                                    importedFqNames,
                                    movedPaths,
                                )?.let { importFqName -> missingImports += importFqName }
                            }
                            val retainedOperators = resolvedOperators.mapNotNull { symbol ->
                                samePackageTopLevelName(symbol, originalPackage, movedPaths)
                            }
                            retainedOperators
                                .filter { importFqName ->
                                    importFqName !in importedFqNames && originalPackage !in starPackages
                                }
                                .forEach { importFqName -> missingImports += importFqName }
                            // A compound indexed write needs the companion setter even when the
                            // getter is a member: the array reference exposes only `get`, so a
                            // staying top-level `set` would otherwise be lost without an import.
                            if (expression.isCompoundWrite()) {
                                expression.selectedIndexedSetter()?.let { setter ->
                                    file.shadowedDependency(
                                        setter,
                                        destination,
                                        destinationNames,
                                        importedFqNames,
                                        movedPaths,
                                    )?.let { importFqName -> missingImports += importFqName }
                                }
                                samePackageIndexedSetter(expression, originalPackage, movedPaths)
                                    ?.takeIf { importFqName ->
                                        importFqName !in importedFqNames && originalPackage !in starPackages
                                    }
                                    ?.let { importFqName -> missingImports += importFqName }
                            }
                        }

                        // A delegated property spells neither accessor: `val value by Delegate()`
                        // calls a top-level `provideDelegate` and then `getValue`/`setValue`
                        // through overload resolution, so those operators have to follow the file
                        // when it leaves the package that made them visible.
                        override fun visitProperty(property: KtProperty) {
                            super.visitProperty(property)
                            val delegateType = property.delegateExpression?.expressionType ?: return
                            // A member provider wins over extensions and needs no import, but its
                            // return type still decides which accessor the property selects.
                            // A provider moving with the file is still selected by the property
                            // after the move, so it decides the accessor type too; only its
                            // import is skipped below. A provider imported from another package
                            // survives the move unchanged and is found through the property's
                            // scope, not through the old package.
                            val provider = delegateType.memberDelegateOperator("provideDelegate")
                                ?: property.scopeDelegateOperator(delegateType, "provideDelegate")
                                ?: samePackageDelegateOperator(
                                    delegateType,
                                    "provideDelegate",
                                    originalPackage,
                                    movedPaths = null,
                                )
                            // `provideDelegate` runs before the accessors and its return type is the
                            // type the accessors are selected on, so it replaces the expression
                            // type. A generic provider returns `Provided<T>`, so the type has to
                            // be instantiated from the delegate receiver first.
                            val accessorType = provider?.returnTypeInferredFrom(delegateType) ?: delegateType
                            val operators = buildList {
                                if (provider != null) add(provider)
                                property.selectedDelegateAccessor(
                                    accessorType,
                                    "getValue",
                                    originalPackage,
                                    movedPaths,
                                )?.let { symbol -> add(symbol) }
                                if (property.isVar) {
                                    property.selectedDelegateAccessor(
                                        accessorType,
                                        "setValue",
                                        originalPackage,
                                        movedPaths,
                                    )?.let { symbol -> add(symbol) }
                                }
                            }
                            operators.forEach { symbol ->
                                file.shadowedDependency(
                                    symbol,
                                    destination,
                                    destinationNames,
                                    importedFqNames,
                                    movedPaths,
                                )?.let { importFqName -> missingImports += importFqName }
                            }
                            operators.asSequence()
                                .mapNotNull { symbol ->
                                    samePackageTopLevelName(symbol, originalPackage, movedPaths)
                                }
                                .filter { importFqName ->
                                    importFqName !in importedFqNames && originalPackage !in starPackages
                                }
                                .forEach { importFqName -> missingImports += importFqName }
                        }
                    })
                }
            }
            if (missingImports.isNotEmpty()) updates[file] = missingImports
        }
        return updates
    }

    /**
     * Resolves which top-level declaration a moved file's simple name still needs to import.
     *
     * Only declarations that stay behind in the file's former package qualify. Class-like and
     * constructor symbols are matched through their class id first: a constructor call resolves to
     * a constructor symbol whose PSI is the constructor inside the class declaration (never a
     * [KtFile] child), so the PSI branch alone would reject explicit-constructor calls. Java classes
     * also have no [KtNamedDeclaration] and are matched the same way. Remaining Kotlin top-level
     * callables are identified through their PSI.
     *
     * @param symbol symbol resolved for the simple-name reference inside a moved file
     * @param originalPackage package the moved file declares before the change
     * @param movedPaths paths of the files whose package changes
     * @return FQN of the declaration to import, or `null` when no import is required
     */
    private fun samePackageTopLevelName(
        symbol: KaSymbol,
        originalPackage: FqName,
        movedPaths: Set<String>,
    ): FqName? {
        val fqName = topLevelDeclarationFqName(symbol) ?: return null
        if (fqName.parent() != originalPackage) return null
        // A declaration that moves with its file follows the file, so it needs no import.
        if (symbol.psi?.containingFile?.virtualFile?.path in movedPaths) return null
        return fqName
    }

    /**
     * Returns the fully qualified name of a top-level declaration, or `null` for members and locals.
     *
     * Class-like and constructor symbols are matched through their class id; callables through
     * their callable id, which also covers dependency JARs without source PSI. Remaining Kotlin
     * top-level declarations are recognized by having a [KtFile] as their parent.
     *
     * @param symbol symbol resolved for a reference
     * @return the declaration's FQN, or `null` when it is not a top-level declaration
     */
    private fun topLevelDeclarationFqName(symbol: KaSymbol): FqName? {
        val psi = symbol.psi
        val classId = when (symbol) {
            is KaConstructorSymbol -> symbol.containingClassId
            is KaClassLikeSymbol -> symbol.classId
            else -> null
        }
        if (classId != null) {
            if (classId.isLocal || classId.isNestedClass) return null
            return classId.asSingleFqName()
        }
        // A callable from a Kotlin dependency JAR has no source PSI; its id is the only way to
        // tell that it is a top-level declaration.
        if (symbol is KaCallableSymbol) {
            val callableId = symbol.callableId ?: return null
            if (callableId.className != null) return null
            return callableId.asSingleFqName()
        }
        if (psi !is KtNamedDeclaration) return null
        if (psi.parent !is KtFile) return null
        val name = psi.name ?: return null
        return psi.containingKtFile.packageFqName.child(Name.identifier(name))
    }

    /**
     * Collects the top-level names the destination package declares after the move.
     *
     * A same-package declaration outranks a wildcard or default import, so a reference bound by
     * one of those needs an explicit import once the file changes into that package. Declarations
     * living in files that leave the destination in the same operation are excluded, while those
     * arriving with the moved files are included.
     *
     * @param destination package the current file is changing to
     * @param movedFiles Kotlin sources whose declared package changes
     * @param targets mapped files with their final declared packages
     * @return top-level simple names the destination package will contain
     */
    @OptIn(KaExperimentalApi::class)
    context(_: KaSession)
    private fun destinationDeclarationNames(
        destination: FqName,
        movedFiles: Collection<KtFile>,
        targets: Map<KtFile, FqName>,
    ): Set<String> {
        val names = linkedSetOf<String>()
        val leavingPaths = movedFiles
            .filter { movedFile -> targets[movedFile] != destination }
            .mapNotNull { movedFile -> movedFile.virtualFile?.path }
            .toSet()
        findPackage(destination)?.packageScope?.declarations.orEmpty().forEach { symbol ->
            val path = symbol.psi?.containingFile?.virtualFile?.path
            if (path != null && path in leavingPaths) return@forEach
            (symbol as? KaNamedSymbol)?.name?.asString()?.let(names::add)
        }
        movedFiles.forEach { movedFile ->
            if (targets[movedFile] != destination) return@forEach
            movedFile.declarations.forEach { declaration ->
                (declaration as? KtNamedDeclaration)?.name?.let(names::add)
            }
        }
        return names
    }

    /**
     * Returns the import a wildcard- or default-import binding needs after the move, if any.
     *
     * The reference currently resolves to a top-level declaration of another package through a
     * star or default import. If the destination package declares the same simple name, that
     * declaration outranks the import and would silently take over the reference. An explicit
     * import of the resolved declaration preserves the meaning; when the file already binds the
     * name explicitly, nothing changes and the rewrite is safe.
     *
     * @param expression simple-name reference inside the moved file
     * @param symbol declaration the reference currently resolves to
     * @param originalPackage package the moved file declares before the change
     * @param destination package the moved file changes to
     * @param destinationNames top-level names the destination package will contain
     * @param importedFqNames unaliased imports already present in the file
     * @return FQN to import, or `null` when the binding is unaffected
     */
    private fun KtFile.shadowedWildcardImport(
        expression: KtSimpleNameExpression,
        symbol: KaSymbol,
        destination: FqName,
        destinationNames: Set<String>,
        importedFqNames: Set<FqName>,
        movedPaths: Set<String>,
    ): FqName? {
        if (destinationNames.isEmpty()) return null
        // A qualified access resolves through its qualifier, which neither an import nor a
        // same-package declaration can capture — with one exception: an extension call on a value
        // receiver (`1.choose()`, `box.choose()`) selects its callee through the file's scope.
        if (expression.qualifiedReceiver() != null && (symbol as? KaCallableSymbol)?.isExtension != true) {
            return null
        }
        val name = expression.getReferencedName()
        if (name !in destinationNames) return null
        // A type alias is spelled by its own name; a declaration of another package that only
        // expands the alias cannot be imported under the spelled name.
        val fqName = topLevelDeclarationFqName(symbol) ?: return null
        if (fqName.shortName().asString() != name) return null
        return shadowedDependency(symbol, destination, destinationNames, importedFqNames, movedPaths)
    }

    /**
     * Returns the import that keeps an implicitly selected declaration bound after the move.
     *
     * Convention operators (`component1`, `iterator`, `get`, `getValue`) spell no name, so a
     * destination declaration of the same name would outrank the wildcard or default import that
     * currently selects them. The resolved declaration's explicit import keeps its priority.
     *
     * @param symbol declaration the reference currently resolves to
     * @param destination package the moved file changes to
     * @param destinationNames top-level names the destination package will contain
     * @param importedFqNames unaliased imports already present in the file
     * @param movedPaths paths of the files whose package changes
     * @return FQN to import, or `null` when the binding is unaffected
     */
    private fun KtFile.shadowedDependency(
        symbol: KaSymbol,
        destination: FqName,
        destinationNames: Set<String>,
        importedFqNames: Set<FqName>,
        movedPaths: Set<String>,
    ): FqName? {
        if (destinationNames.isEmpty()) return null
        val fqName = topLevelDeclarationFqName(symbol) ?: return null
        val name = fqName.shortName().asString()
        if (name !in destinationNames) return null
        // A declaration moving with its file follows it and no import can express that.
        if (symbol.psi?.containingFile?.virtualFile?.path in movedPaths) return null
        // A declaration of the destination package is already bound through the package itself.
        if (fqName.parent() == destination) return null
        if (fqName in importedFqNames) return null
        // An explicit import, aliased or not, keeps its priority over same-package declarations.
        val explicitlyBound = importDirectives.any { directive ->
            if (directive.isAllUnder) return@any false
            val alias = directive.aliasName
            if (alias != null) alias == name else directive.importedFqName?.shortName()?.asString() == name
        }
        if (explicitlyBound) return null
        // Without an explicit import, a resolved top-level name is visible through the file's own
        // package, a wildcard import, or Kotlin's default imports — all outranked by the
        // destination's same-package declaration.
        return fqName
    }

    /**
     * Finds the `set` extension a compound indexed write needs from the original package.
     *
     * K2 does not expose the implicit setter, so it is looked up by name and applicability on the
     * receiver expression's type. Only a declaration that stays behind qualifies, mirroring the
     * `get` operator of the same access.
     *
     * @param expression indexed access in a compound write position
     * @param originalPackage package the moved file declares before the change
     * @param movedPaths paths of the files whose package changes
     * @return FQN of the remaining `set` extension, or `null` when none applies
     */
    @OptIn(KaExperimentalApi::class)
    context(_: KaSession)
    private fun samePackageIndexedSetter(
        expression: KtArrayAccessExpression,
        originalPackage: FqName,
        movedPaths: Set<String>,
    ): FqName? {
        val receiverType = expression.arrayExpression?.expressionType ?: return null
        val candidates = findPackage(originalPackage)?.packageScope?.declarations
            ?.filterIsInstance<KaCallableSymbol>()
            ?.filter { symbol ->
                (symbol as? KaNamedSymbol)?.name?.asString() == "set" && symbol.isExtension
            }
            .orEmpty()
            .toList()
        // The receiver alone cannot tell `Box.set` from the more specific `SubBox.set`; only the
        // overload resolution result may be imported.
        val selected = selectExtension(candidates, receiverType, expression.setterArgumentTypes())
            ?: return null
        return samePackageTopLevelName(selected, originalPackage, movedPaths)
    }

    /**
     * Returns the member delegate operator of [name] on this type, or `null` when it has none.
     *
     * A member always wins over an extension, so the compiler never falls back to a same-package
     * operator when the type declares a fitting one. Matching by name alone would treat an
     * ordinary member whose signature does not fit the delegation protocol as an accessor.
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
     * Finds the delegate accessor the property currently selects.
     *
     * A member accessor wins over every extension and needs no import, so the lookup only applies
     * when the receiver type declares no member of that name. Otherwise the property's own scope
     * decides, exactly as the compiler resolves the implicit call: a wildcard-imported accessor is
     * found as well as a same-package one. The original package stays a fallback for scopes that
     * do not expose its declarations.
     *
     * @param accessorType type the accessor is selected on
     * @param operatorName `getValue` or `setValue`
     * @param originalPackage package the moved file declares before the change
     * @param movedPaths paths of the files whose package changes
     * @return the selected accessor extension, or `null` when none applies
     */
    context(_: KaSession)
    private fun KtProperty.selectedDelegateAccessor(
        accessorType: KaType,
        operatorName: String,
        originalPackage: FqName,
        movedPaths: Set<String>,
    ): KaCallableSymbol? {
        if (accessorType.memberDelegateOperator(operatorName) != null) return null
        return scopeDelegateOperator(accessorType, operatorName)
            ?: samePackageDelegateOperator(accessorType, operatorName, originalPackage, movedPaths)
    }

    /**
     * Finds the delegate operator of [name] the file's scope selects on [type].
     *
     * Unlike [samePackageDelegateOperator], this lookup follows the element's real visibility: an
     * operator imported from any package stays bound after the move, and a provider's return type
     * still decides which `getValue`/`setValue` accessor applies. Receiver applicability goes
     * through the analysis API's extension check so a generic receiver is inferred.
     *
     * @param type type the operator is selected on
     * @param operatorName `provideDelegate`, `getValue`, or `setValue`
     * @return the selected extension, or `null` when none is in scope
     */
    @OptIn(KaExperimentalApi::class)
    context(_: KaSession)
    private fun KtElement.scopeDelegateOperator(type: KaType, operatorName: String): KaCallableSymbol? = runCatching {
        val identifier = Name.identifier(operatorName)
        // Scopes follow import priority, so the first one that provides an applicable operator
        // decides; inside it, the most specific receiver wins (`Int.getValue` over
        // `Number.getValue`), exactly as overload resolution does.
        containingKtFile.scopeContext(this).scopes.forEach { scopeWithKind ->
            val candidates = scopeWithKind.scope.callables(identifier)
                .filter { symbol -> symbol.isExtension && symbol.isDelegateOperatorShape(operatorName) }
                .toList()
            selectExtension(candidates, type)?.let { selected -> return@runCatching selected }
        }
        null
    }.getOrNull()

    /**
     * Finds a delegate operator of the original package selected on [type].
     *
     * K2 does not expose the implicit `provideDelegate`/`getValue`/`setValue` calls of a delegated
     * property, so the operator is looked up by name, signature shape, and receiver applicability
     * on the type it is selected on. When [movedPaths] is `null`, every declaration of the
     * original package qualifies — a provider moving with the file still decides which accessor
     * the property selects. Otherwise only a declaration that stays behind qualifies, mirroring
     * the indexed-operator lookup.
     *
     * @param type type of the delegate, or of the value a `provideDelegate` returns
     * @param operatorName `provideDelegate`, `getValue`, or `setValue`
     * @param originalPackage package the moved file declares before the change
     * @param movedPaths paths of the files whose package changes, or `null` to accept moving ones
     * @return the selected operator, or `null` when none applies
     */
    @OptIn(KaExperimentalApi::class)
    context(_: KaSession)
    private fun samePackageDelegateOperator(
        type: KaType,
        operatorName: String,
        originalPackage: FqName,
        movedPaths: Set<String>?,
    ): KaCallableSymbol? {
        val candidates = findPackage(originalPackage)?.packageScope?.declarations
            ?.filterIsInstance<KaCallableSymbol>()
            ?.filter { symbol ->
                (symbol as? KaNamedSymbol)?.name?.asString() == operatorName &&
                    symbol.isDelegateOperatorShape(operatorName) &&
                    symbol.canBeCalledAsExtensionOn(type) &&
                    (movedPaths == null || samePackageTopLevelName(symbol, originalPackage, movedPaths) != null)
            }
            .orEmpty()
            .toList()
        // A package can declare several overloads of the operator; only the most specific
        // applicable one is selected for this receiver.
        return selectExtension(candidates, type)
    }

    /**
     * Resolves a type alias that a moved file still needs to import from its former package.
     *
     * A constructor call through a type alias resolves to the constructor of the underlying class,
     * which may live in another package. The source keeps spelling the alias, so the alias itself
     * must be imported when the file leaves the package that declared it.
     *
     * @param expression simple-name reference inside a moved file
     * @param symbol symbol K2 resolved for the reference
     * @param originalPackage package the moved file declares before the change
     * @param movedPaths paths of the files whose package changes
     * @return FQN of the same-package type alias to import, or `null` when there is none
     */
    context(_: KaSession)
    private fun samePackageTypeAliasName(
        expression: KtSimpleNameExpression,
        symbol: KaSymbol,
        originalPackage: FqName,
        movedPaths: Set<String>,
    ): FqName? {
        val classId = when (symbol) {
            is KaConstructorSymbol -> symbol.containingClassId
            is KaClassLikeSymbol -> symbol.classId
            else -> null
        } ?: return null
        if (classId.isLocal || classId.isNestedClass) return null
        val referencedName = expression.getReferencedName()
        // The resolved constructor may belong to the alias itself or to the class it expands to,
        // depending on how K2 names the symbol; the package-scope lookup below settles it either
        // way, so a name equality with the class id must not skip it.
        val file = expression.containingKtFile
        // Any explicit import of the spelled name binds it wherever it lives and stays valid.
        val importedName = file.importDirectives.any { directive ->
            !directive.isAllUnder &&
                (directive.aliasName ?: directive.importedFqName?.shortName()?.asString()) == referencedName
        }
        if (importedName) return null
        // The alias must really be declared in the package the file is leaving, and must not
        // travel with the file itself.
        val alias = (findPackage(originalPackage)?.packageScope
            ?.classifiers?.firstOrNull { classifier -> classifier.name?.asString() == referencedName }
            as? KaTypeAliasSymbol) ?: return null
        if (alias.psi?.containingFile?.virtualFile?.path in movedPaths) return null
        return originalPackage.child(Name.identifier(referencedName))
    }

    /**
     * Tells whether [this] is spelled with an import alias that binds [fqName].
     *
     * `import a.Helper as OriginalHelper` already keeps the dependency: the alias resolves the
     * declaration without a further import, and adding the unaliased name would collide with a
     * same-named class imported from elsewhere.
     */
    private fun KtSimpleNameExpression.isAliasBound(fqName: FqName): Boolean {
        val name = getReferencedName()
        return containingKtFile.importDirectives.any { directive ->
            directive.aliasName == name && directive.importedFqName == fqName
        }
    }

    /**
     * Returns the receiver of the qualified access this expression selects.
     *
     * A callee is wrapped in a [KtCallExpression] (`a.Helper(1)`), so the qualified access is found
     * through that call node rather than directly through the callee's parent. A type reference is
     * wrapped in a [KtUserType] whose qualifier spells the receiver (`a.Helper`).
     *
     * @return the receiver expression, or `null` when this name is not a qualified selector
     */
    private fun KtSimpleNameExpression.qualifiedReceiver(): KtElement? {
        val selector = (parent as? KtCallExpression)?.takeIf { it.calleeExpression == this } ?: this
        return when (val qualified = selector.parent as? KtDotQualifiedExpression) {
            null -> {
                val userType = parent as? KtUserType
                if (userType == null || userType.referenceExpression != this) return null
                userType.qualifier ?: return null
            }
            else -> {
                if (qualified.selectorExpression != selector) return null
                qualified.receiverExpression
            }
        }
    }

    /**
     * Tells whether [this] is the selector of a qualified access whose receiver spells out
     * [packageFqName], in which case the reference resolves without an import.
     *
     * The receiver text alone cannot prove that it denotes the package: an extension access such as
     * `p.extra` on a parameter named `p` spells the package of the top-level extension property it
     * resolves to while actually using a value receiver. Callers therefore resolve
     * [qualifiedReceiver]'s leftmost name first and treat a variable symbol as an ordinary receiver.
     *
     * @param packageFqName package the reference may already name in full
     * @return `true` when the receiver text is that package or a prefix of it
     */
    private fun KtSimpleNameExpression.isPackageQualified(packageFqName: FqName): Boolean {
        val receiver = qualifiedReceiver() ?: return false
        val receiverText = receiver.text
        val packageName = packageFqName.asString()
        return packageName == receiverText || packageName.startsWith("$receiverText.")
    }

    /**
     * Separates a package directive from adjacent code when it replaced an empty directive.
     *
     * [K2ChangePackageRefactoringProcessor.updatePackages] replaces the empty directive of a
     * root-package file in place, which leaves `package dest` glued to the next declaration or,
     * for a file with file annotations, to the closing parenthesis of the last annotation. Both
     * sides need whitespace so the directive stays a standalone line between them.
     *
     * @param text source text after the package mutation
     * @param targetPackage package written by the processor
     * @param startOffset captured offset where the new directive begins
     * @return text whose package directive is surrounded by whitespace or file boundaries
     */
    private fun ensurePackageDirectiveSeparator(text: String, targetPackage: FqName, startOffset: Int): String {
        if (targetPackage.isRoot) return text
        val directive = "package ${targetPackage.asString()}"
        if (!text.startsWith(directive, startOffset)) return text
        var result = text
        var directiveEnd = startOffset + directive.length
        if (startOffset > 0 && !result[startOffset - 1].isWhitespace()) {
            result = result.substring(0, startOffset) + "\n" + result.substring(startOffset)
            directiveEnd++
        }
        if (directiveEnd < result.length && !result[directiveEnd].isWhitespace()) {
            result = result.substring(0, directiveEnd) + "\n\n" + result.substring(directiveEnd)
        }
        return result
    }

    /** PSI-captured facts required to insert imports without rescanning the changed source text. */
    private data class ImportInsertion(
        val existingFqNames: Set<FqName>,
        val offset: Int,
        val prefix: String,
        val suffix: String,
    )

    /**
     * Captures where a file's import block starts and which imports it already declares.
     *
     * The offset refers to the pre-mutation text. A file with imports gains new lines at its first
     * directive; otherwise the insertion follows the package directive or, for a root-package
     * file, precedes the first declaration so header comments and file annotations stay on top.
     * A root-package file without declarations (for example one holding only file annotations)
     * appends the imports at its end. Only real PSI import directives and the package directive
     * are consulted, so text inside literals or comments cannot influence either the duplicate
     * check or the boundary.
     *
     * @param file participant whose original PSI is still valid
     * @return captured insertion point, or `null` when the file cannot host an import
     */
    private fun importInsertionFor(file: KtFile): ImportInsertion? {
        // An alias binds only its alias, not the imported name, so an unaliased use still needs
        // its own import even when an aliased directive for the same FQN exists.
        val existingFqNames = file.importDirectives
            .filterNot { directive -> directive.isAllUnder || directive.aliasName != null }
            .mapNotNull { directive -> directive.importedFqName }
            .toSet()
        file.importDirectives.firstOrNull()?.let { firstImport ->
            return ImportInsertion(existingFqNames, firstImport.textRange.startOffset, "", "\n")
        }
        if (!file.packageFqName.isRoot) {
            val directive = file.packageDirective ?: return null
            if (directive.text.isBlank()) return null
            return ImportInsertion(existingFqNames, directive.textRange.endOffset, "\n\n", "")
        }
        // A root-package file may contain only file annotations. Kotlin allows imports after the
        // annotation list, so the end of the file is a valid insertion point there.
        val declarationStart = file.declarations.firstOrNull()?.textRange?.startOffset
        if (declarationStart == null) {
            val prefix = if (file.text.endsWith("\n")) "\n" else "\n\n"
            return ImportInsertion(existingFqNames, file.textLength, prefix, "")
        }
        return ImportInsertion(existingFqNames, declarationStart, "", "\n\n")
    }

    /**
     * Adds imports that no longer arrive implicitly from the package or a wildcard import.
     *
     * Placement and duplicate detection come from PSI captured before the package mutation. The
     * insertion offset is realigned by the package change and by the net length of accepted
     * replacements that end before it.
     *
     * @param usageText source text of the caller after reference replacement
     * @param imports declarations that need an explicit import
     * @param insertion captured header facts for this file, or `null` when none were captured
     * @param appliedEdits replacements already applied to [usageText], in its coordinates
     * @param shift length difference caused by the package directive change
     * @return final source text with imports added at the captured boundary
     */
    private fun addSamePackageImports(
        usageText: String,
        imports: Set<FqName>,
        insertion: ImportInsertion?,
        appliedEdits: List<TextEdit>,
        shift: Int,
    ): String {
        if (imports.isEmpty() || insertion == null) return usageText
        val missingImports = imports.filterNot { imported -> imported in insertion.existingFqNames }
            .map { imported -> "import ${KotlinSourceNames.renderFqName(imported)}" }
        if (missingImports.isEmpty()) return usageText
        val shiftedOffset = insertion.offset + shift
        val offset = shiftedOffset + appliedEdits
            .filter { edit -> edit.end <= shiftedOffset }
            .sumOf { edit -> edit.replacement.length - (edit.end - edit.start) }
        if (offset !in 0..usageText.length) return usageText
        val importLines = missingImports.joinToString(separator = "\n")
        return usageText.substring(0, offset) +
            insertion.prefix + importLines + insertion.suffix +
            usageText.substring(offset)
    }

    /** Captures usage files before K2 reference rebinding can invalidate individual usage elements. */
    private fun filesWithUsages(usages: Collection<UsageInfo>): List<KtFile> =
        usages.mapNotNull { usage -> usage.element?.containingFile as? KtFile }.distinct()

    /** One replacement of `[start, end)` in the source text captured before package mutation. */
    private data class TextEdit(val start: Int, val end: Int, val replacement: String)

    /**
     * Captures the import directives that must follow moved declarations.
     *
     * Paths are matched by FQN prefix, so an import of a nested declaration keeps its suffix, and
     * only directives that actually target a moved declaration are recorded. A wildcard import
     * follows only when its prefix itself is a moved class; a package wildcard keeps binding
     * declarations that stay behind and is retargeted or dropped only when no source file remains
     * in its package. References themselves are captured separately from K2-resolved usages.
     *
     * @param participants files whose text may change
     * @param movedDeclarations old top-level declaration FQNs mapped to their new FQNs
     * @param movedClassFqNames original FQNs of moved classes and objects
     * @param movedPaths paths of the files whose package changes
     * @return per-file replacements keyed by captured source offsets
     */
    private fun captureImportEdits(
        participants: Collection<KtFile>,
        movedDeclarations: Map<FqName, FqName>,
        movedClassFqNames: Set<FqName>,
        movedPaths: Set<String>,
    ): Map<KtFile, List<TextEdit>> {
        if (movedDeclarations.isEmpty()) return emptyMap()
        val mappings = movedDeclarations.entries
            .sortedByDescending { (oldFqName, _) -> oldFqName.pathSegments().size }
        val edits = linkedMapOf<KtFile, MutableSet<TextEdit>>()
        participants.forEach { file ->
            file.importDirectives.forEach { directive ->
                val importedReference = directive.importedReference ?: return@forEach
                // Ranges come from PSI: `importPath.pathStr` is a rendered path and can differ
                // from the source spelling (backticks, whitespace), so searching for it corrupts.
                val pathStart = directive.textRange.startOffset + importPathStart(directive.text)
                val pathEnd = importedReference.textRange.endOffset
                if (pathStart >= pathEnd) return@forEach
                if (directive.isAllUnder) {
                    // A package wildcard keeps importing whatever stays behind, so only a
                    // wildcard rooted in a moved class (or a class nested inside it) follows
                    // that class to its new package, keeping the nested suffix and `.*`.
                    val imported = directive.importedFqName ?: return@forEach
                    val movedClass = longestMovedClassPrefix(imported, movedClassFqNames)
                    if (movedClass == null) {
                        obsoletePackageWildcardEdit(file, directive, imported, movedDeclarations, movedPaths)
                            ?.let { edit -> edits.getOrPut(file, ::linkedSetOf) += edit }
                        return@forEach
                    }
                    val newPrefix = movedDeclarations[movedClass] ?: return@forEach
                    val suffixSegments = imported.pathSegments().drop(movedClass.pathSegments().size)
                    val suffixText = if (suffixSegments.isEmpty()) {
                        ""
                    } else {
                        suffixSegments.joinToString(separator = ".", prefix = ".") { segment ->
                            KotlinSourceNames.renderIdentifier(segment.asString())
                        }
                    }
                    edits.getOrPut(file, ::linkedSetOf) +=
                        TextEdit(pathStart, pathEnd, KotlinSourceNames.renderFqName(newPrefix) + suffixText)
                    return@forEach
                }
                val replacement = rewriteImportedPath(
                    directive.identifiersWithin(pathStart, pathEnd),
                    mappings,
                    movedClassFqNames,
                ) ?: return@forEach
                edits.getOrPut(file, ::linkedSetOf) += TextEdit(pathStart, pathEnd, replacement)
            }
        }
        return edits.mapValues { (_, fileEdits) -> fileEdits.toList() }
    }

    /**
     * Builds the edit that keeps a package wildcard resolvable when its package loses every source.
     *
     * A wildcard import of a package that no longer exists is an unresolved reference even when
     * every moved usage was retargeted, so the directive is removed once no declaration stays
     * behind. Retargeting it to the single destination package would be worse: it would also
     * import every unrelated declaration already in that package, which can capture names the
     * caller currently resolves through another wildcard import. The incoming pass already
     * captured an explicit import for every moved declaration the caller uses.
     *
     * @param file file containing the directive
     * @param directive wildcard import whose prefix is not a moved class
     * @param imported package named by the wildcard
     * @param movedDeclarations old top-level declaration FQNs mapped to their new FQNs
     * @param movedPaths paths of the files whose package changes
     * @return replacement edit, or `null` when the package keeps declarations or nothing moved
     */
    private fun obsoletePackageWildcardEdit(
        file: KtFile,
        directive: KtImportDirective,
        imported: FqName,
        movedDeclarations: Map<FqName, FqName>,
        movedPaths: Set<String>,
    ): TextEdit? {
        val movedFromPackage = movedDeclarations.filterKeys { oldFqName -> oldFqName.parent() == imported }
        if (movedFromPackage.isEmpty()) return null
        val service = KotlinMoveUsageSearchService.getInstance() ?: return null
        if (service.hasDeclarationsInPackage(ktFile.project, imported, movedPaths)) return null
        // The package keeps no declaration, so neither the old wildcard nor a broadened one is
        // safe: remove the directive together with the rest of its line.
        val text = file.text
        var end = directive.textRange.endOffset
        while (end < text.length &&
            (text[end] == ' ' || text[end] == '\t' || text[end] == '\r' || text[end] == '\n')
        ) {
            end++
        }
        val start = directive.textRange.startOffset
        return if (start < end) TextEdit(start, end, "") else null
    }

    /**
     * Returns the deepest moved class whose FQN is [imported] or a prefix of it.
     *
     * Matching the longest prefix keeps a nested wildcard (`old.Outer.Inner.*`) attached to the
     * moved class (`old.Outer`) with `Inner` as its suffix.
     *
     * @param imported FQN named by a wildcard import directive
     * @param movedClassFqNames old FQNs of moved top-level classes
     * @return the longest matching moved class FQN, or `null` when none matches
     */
    private fun longestMovedClassPrefix(imported: FqName, movedClassFqNames: Set<FqName>): FqName? =
        movedClassFqNames
            .filter { candidate -> candidate.isPrefixOf(imported) }
            .maxByOrNull { candidate -> candidate.pathSegments().size }

    /**
     * Tells whether this FQN equals [fqName] or is one of its package/class prefixes.
     *
     * @param fqName fully qualified name to test
     * @return `true` when this FQN names the same declaration or one of its enclosing segments
     */
    private fun FqName.isPrefixOf(fqName: FqName): Boolean {
        val prefix = pathSegments()
        val segments = fqName.pathSegments()
        return segments.size >= prefix.size && segments.subList(0, prefix.size) == prefix
    }

    /**
     * Finds where an import path starts inside a directive's text.
     *
     * @param directiveText exact source text of the import directive
     * @return offset of the first non-whitespace character after the `import` keyword
     */
    private fun importPathStart(directiveText: String): Int {
        val keywordEnd = directiveText.indexOf("import").let { index ->
            if (index < 0) 0 else index + "import".length
        }
        var pathStart = keywordEnd
        while (pathStart < directiveText.length && directiveText[pathStart].isWhitespace()) {
            pathStart++
        }
        return pathStart
    }

    /**
     * Rewrites the package prefix of an imported path, keeping alias and nested-class suffixes.
     *
     * Segments are PSI identifiers, so backticks, whitespace, and interspersed comments in the
     * source text do not affect matching. The suffix is rendered again with the escaping Kotlin
     * requires.
     *
     * Only moved classifiers own a suffix: their nested declarations really do follow them, so
     * `import old.Outer.Nested` becomes `import new.Outer.Nested`. A top-level function or property
     * shares its name with packages freely (`fun tools()` and package `p.tools`), so a callable
     * mapping matches the imported path exactly and never captures a longer path.
     *
     * @param segments identifier names of the imported path, in source order
     * @param mappings moved declarations ordered from the longest FQN to the shortest
     * @param classifierFqNames original FQNs of moved classes and objects
     * @return retargeted path text, or `null` when the import does not target a moved declaration
     */
    private fun rewriteImportedPath(
        segments: List<String>,
        mappings: List<Map.Entry<FqName, FqName>>,
        classifierFqNames: Set<FqName>,
    ): String? {
        mappings.forEach { (oldFqName, newFqName) ->
            val oldSegments = oldFqName.pathSegments().map { segment -> segment.asString() }
            if (segments.size < oldSegments.size) return@forEach
            if (segments.take(oldSegments.size) != oldSegments) return@forEach
            if (oldFqName !in classifierFqNames && segments.size != oldSegments.size) return@forEach
            val suffix = segments.drop(oldSegments.size)
            val suffixText = suffix.joinToString(separator = ".", prefix = if (suffix.isEmpty()) "" else ".") { name ->
                KotlinSourceNames.renderIdentifier(name)
            }
            return KotlinSourceNames.renderFqName(newFqName) + suffixText
        }
        return null
    }

    /**
     * Collects the simple-name identifiers of this element inside `[start, end)`.
     *
     * Identifiers come from PSI, so whitespace, backticks, and comments between them are ignored
     * while the captured replacement range stays in source coordinates. Type arguments and call
     * arguments located after [end] are excluded.
     *
     * @param start first offset of the region to read
     * @param end offset just past the region
     * @return referenced names in source order
     */
    private fun PsiElement.identifiersWithin(start: Int, end: Int): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        fun visit(element: PsiElement) {
            val range = element.textRange
            if (range.endOffset <= start || range.startOffset >= end) return
            if (element is KtSimpleNameExpression) {
                if (range.startOffset >= start && range.endOffset <= end) {
                    found += range.startOffset to element.getReferencedName()
                }
                return
            }
            // Import path qualifiers are plain identifier tokens rather than name references.
            if (element.node?.elementType == KtTokens.IDENTIFIER) {
                found += range.startOffset to element.text.removeSurrounding("`")
                return
            }
            for (child in element.children) visit(child)
        }
        visit(this)
        return found.sortedBy { (offset, _) -> offset }.map { (_, name) -> name }
    }

    /**
     * Source range, PSI identifiers and leftmost name of the qualified chain ending at a reference.
     *
     * [identifiers] ignores source spelling (backticks, comments), and [rootReference] is the
     * leftmost simple name of the chain, which tells a package qualifier apart from an expression
     * that merely spells the same text.
     */
    private data class QualifiedChain(
        val startOffset: Int,
        val endOffset: Int,
        val identifiers: List<String>,
        val rootReference: KtSimpleNameExpression?,
    )

    /**
     * Tells whether this chain's PSI identifiers spell [fqName].
     *
     * Backticks, whitespace, and comments between segments change the source spelling but not the
     * identifiers K2 resolved, so only the names are compared while the captured PSI range still
     * delimits the replacement.
     *
     * @param fqName fully qualified name of the declaration K2 resolved
     * @return `true` when the chain identifiers denote exactly that FQN
     */
    private fun QualifiedChain.matches(fqName: FqName): Boolean =
        identifiers == fqName.pathSegments().map { segment -> segment.asString() }

    /**
     * Extends a reference expression through the qualified receiver chain that contains it.
     *
     * `old.pkg.moved()` visits the `moved` reference; climbing through qualified expressions, their
     * call nodes, and user types yields the identifiers `old`, `pkg`, `moved` while dropping any
     * argument list or type argument suffix.
     *
     * The chain must consist only of names. A receiver that is a call, index access, or any other
     * expression (`a.b().moved()`) would contribute its own identifiers to the captured range while
     * the replacement deletes the expression itself, so such a chain is rejected rather than
     * matched by name alone.
     *
     * @return the chain ending at this element, or `null` when its receiver is not a plain name chain
     */
    private fun KtElement.qualifiedChain(): QualifiedChain? {
        var current: KtElement = this
        var start = textRange.startOffset
        val end = textRange.endOffset
        var rootReference: KtSimpleNameExpression? = null
        while (true) {
            val parent = current.parent
            when {
                parent is KtDotQualifiedExpression && parent.selectorExpression == current -> {
                    val receiver = parent.receiverExpression
                    if (!receiver.isNameChain()) return null
                    start = receiver.textRange.startOffset
                    rootReference = receiver.leadingReference()
                    current = parent
                }
                parent is KtCallExpression && parent.calleeExpression == current -> current = parent
                parent is KtUserType && parent.referenceExpression == current -> {
                    parent.qualifier?.let { qualifier ->
                        if (!qualifier.isNameChain()) return null
                        start = qualifier.textRange.startOffset
                        rootReference = qualifier.leadingReference()
                    }
                    current = parent
                }
                else -> return QualifiedChain(start, end, current.identifiersWithin(start, end), rootReference)
            }
        }
    }

    /**
     * Tells whether this element is a chain of names joined by dots.
     *
     * Only such a chain denotes exactly the identifiers it spells and can be replaced by a
     * package-qualified name as a whole. Calls, index accesses, postfix operators, parentheses and
     * any other expression must keep their own structure.
     *
     * @return `true` when the element consists solely of simple names, qualified name chains and
     * user types
     */
    private fun KtElement.isNameChain(): Boolean = when (this) {
        is KtSimpleNameExpression -> true
        is KtDotQualifiedExpression ->
            selectorExpression?.isNameChain() == true && receiverExpression.isNameChain()
        is KtUserType -> referenceExpression != null && (qualifier?.isNameChain() ?: true)
        else -> false
    }

    /**
     * Returns the leftmost simple name of this receiver expression's qualified chain.
     *
     * A call or any other expression is not a name and yields `null`: resolving it would not tell
     * whether the chain starts from a package.
     *
     * @return the leftmost name, or `null` when the receiver is not a name-based chain
     */
    private fun KtElement.leadingReference(): KtSimpleNameExpression? = when (this) {
        is KtDotQualifiedExpression -> receiverExpression.leadingReference()
        is KtUserType -> qualifier?.leadingReference() ?: referenceExpression
        is KtSimpleNameExpression -> this
        else -> null
    }

    /** Retargeted text and the replacements accepted for it, in its coordinates. */
    private data class AppliedEdits(val text: String, val edits: List<TextEdit>)

    /**
     * Applies replacements captured on the pre-mutation text to the post-mutation text.
     *
     * The package directive replacement is the only length change before the captured ranges, and
     * only ranges starting after its end are shifted: a file annotation precedes the directive in
     * source order and keeps its offsets. Nested ranges are dropped in favour of the widest captured
     * replacement, and edits are applied from the end so earlier offsets stay valid.
     *
     * @param text source text after package mutation
     * @param edits replacements captured on the original text
     * @param shift length difference caused by the package directive change
     * @param shiftAfter original offset after which [shift] applies; `null` shifts every range
     * @return text with all non-overlapping replacements applied plus the accepted edits
     */
    private fun applyTextEdits(
        text: String,
        edits: Collection<TextEdit>,
        shift: Int,
        shiftAfter: Int?,
    ): AppliedEdits {
        if (edits.isEmpty()) return AppliedEdits(text, emptyList())
        val shifted = edits.asSequence()
            .map { edit ->
                val delta = if (shiftAfter == null || edit.start >= shiftAfter) shift else 0
                TextEdit(edit.start + delta, edit.end + delta, edit.replacement)
            }
            .filter { edit -> edit.start >= 0 && edit.end <= text.length && edit.start < edit.end }
            .distinct()
            .sortedWith(compareBy<TextEdit> { it.start }.thenByDescending { it.end })
            .toList()
        val accepted = mutableListOf<TextEdit>()
        shifted.forEach { edit ->
            val contained = accepted.any { acceptedEdit ->
                acceptedEdit.start <= edit.start && edit.end <= acceptedEdit.end
            }
            if (!contained) accepted += edit
        }
        val result = StringBuilder(text)
        accepted.sortedByDescending { edit -> edit.start }.forEach { edit ->
            result.replace(edit.start, edit.end, edit.replacement)
        }
        return AppliedEdits(result.toString(), accepted)
    }
}
