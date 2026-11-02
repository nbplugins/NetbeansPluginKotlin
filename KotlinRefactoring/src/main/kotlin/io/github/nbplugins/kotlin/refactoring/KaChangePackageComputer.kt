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
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.MoveImport
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2ChangePackageDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2ChangePackageRefactoringProcessor
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
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
            val conflicts = processors.flatMap { processor ->
                processor.findConflicts(usages.filterIsInstance<MoveRenameUsageInfo>()).values()
            }
            if (conflicts.isNotEmpty()) return ApplyOutcome.Conflicts(conflicts.distinct())

            val movedDeclarations = files.flatMap { file ->
                file.declarations.mapNotNull { declaration ->
                    (declaration as? KtNamedDeclaration)?.name?.let { name ->
                        file.packageFqName.child(Name.identifier(name)) to
                            targets.getValue(file).child(Name.identifier(name))
                    }
                }
            }.toMap()
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
            // Incoming changes fix callers that lose visibility of a moved declaration, either
            // because they shared its package or because they imported the whole old package.
            // Outgoing imports fix the moved file itself losing visibility of the declarations
            // that stay behind in its former package.
            val incoming = if (updateReferences) findIncomingRetargeting(files, targets) else IncomingRetargeting()
            val outgoingImports = if (updateReferences) findOutgoingSamePackageImports(files, targets) else emptyMap()
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
            val participants = (files + filesWithUsages(usages) + importOnlyUsageFiles + wildcardImportFiles + samePackageImportUpdates.keys + incoming.edits.keys)
                .distinct()
            // Capture replacement ranges while PSI is still valid. Only import directives that
            // target a moved declaration and references that K2 resolved to one are retargeted, so
            // text that merely spells a moved FQN (comments, strings, unrelated chains) is untouched.
            val textEdits = if (updateReferences) {
                val importEdits = captureImportEdits(participants, movedDeclarations, movedClassFqNames)
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
        movedDeclarations.forEach { (declaration, unified) ->
            val (oldFqName, newFqName) = unified
            val declarationFile = declaration.containingKtFile
            val originalPackage = oldFqName.parent()
            session.findUsages(declaration).forEach { reference ->
                val element = reference.element as? KtElement ?: return@forEach
                val file = element.containingFile as? KtFile ?: return@forEach
                // A qualified chain is rewritten in place, so this usage needs no import; an
                // unqualified usage in the same file still adds one when the loop reaches it.
                if (captureQualifiedReference(element, oldFqName, newFqName, edits)) return@forEach
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
                val sharesOriginalPackage = file.packageFqName == originalPackage
                val importsOriginalPackage = file.importDirectives.any { directive ->
                    directive.isAllUnder && directive.importedFqName == originalPackage
                }
                if (!sharesOriginalPackage && !importsOriginalPackage) return@forEach
                // The rewritten alias directive keeps this usage bound, so it needs no
                // unaliased import of its own.
                val simpleName = element as? KtSimpleNameExpression
                if (simpleName != null && simpleName.isAliasBound(oldFqName)) return@forEach
                imports.getOrPut(file, ::linkedSetOf) += newFqName
            }
        }
        return IncomingRetargeting(imports, edits.mapValues { (_, fileEdits) -> fileEdits.toList() })
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
     * @param element reference expression that K2 resolved to the moved declaration
     * @param oldFqName declaration FQN before the package change
     * @param newFqName declaration FQN after the package change
     * @param edits per-file replacement accumulator
     * @return `true` when a qualified chain was replaced in place and therefore needs no import
     */
    private fun captureQualifiedReference(
        element: KtElement,
        oldFqName: FqName,
        newFqName: FqName,
        edits: MutableMap<KtFile, MutableSet<TextEdit>>,
    ): Boolean {
        // A root-package FQN is a bare name. Qualifying such a reference would rewrite
        // declaration-like positions that cannot take a package prefix (`::moved`); imports
        // alone keep root-package bindings intact.
        if (oldFqName.parent().isRoot) return false
        val file = element.containingFile as? KtFile ?: return false
        val chain = element.qualifiedChain() ?: return false
        if (!chain.matches(oldFqName)) return false
        // K2 resolves package references to `null` in this position, so only a concrete
        // non-package symbol proves the chain root is a value/class rather than a package.
        val root = chain.rootReference
        if (root != null) {
            val rootSymbol = runCatching {
                analyze(file) { root.mainReference?.resolveToSymbol() }
            }.getOrNull()
            if (rootSymbol != null && rootSymbol !is KaPackageSymbol) return false
        }
        edits.getOrPut(file, ::linkedSetOf) += TextEdit(chain.startOffset, chain.endOffset, KotlinSourceNames.renderFqName(newFqName))
        return true
    }

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
            val missingImports = linkedSetOf<FqName>()
            runCatching {
                analyze(file) {
                    file.accept(object : KtTreeVisitorVoid() {
                        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                            super.visitSimpleNameExpression(expression)
                            if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                            val reference = expression.references.filterIsInstance<KtReference>().firstOrNull() ?: return
                            val symbol = reference.resolveToSymbol() ?: return
                            val importFqName = samePackageTopLevelName(symbol, originalPackage, movedPaths) ?: return
                            if (importFqName in importedFqNames || originalPackage in starPackages) return
                            if (expression.isPackageQualified(originalPackage)) return
                            if (expression.isPackageQualified(originalPackage)) return
                            if (expression.isAliasBound(importFqName)) return
                            missingImports += importFqName
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
        val psi = symbol.psi
        val classId = when (symbol) {
            is KaConstructorSymbol -> symbol.containingClassId
            is KaClassLikeSymbol -> symbol.classId
            else -> null
        }
        if (classId != null) {
            if (classId.isLocal || classId.isNestedClass) return null
            if (classId.packageFqName != originalPackage) return null
            if (psi?.containingFile?.virtualFile?.path in movedPaths) return null
            return classId.asSingleFqName()
        }
        if (psi !is KtNamedDeclaration) return null
        val containingFile = psi.containingKtFile
        if (containingFile.virtualFile?.path in movedPaths) return null
        if (containingFile.packageFqName != originalPackage) return null
        if (psi.parent !is KtFile) return null
        val name = psi.name ?: return null
        return originalPackage.child(Name.identifier(name))
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
     * Tells whether [this] is the selector of a qualified access whose receiver already spells out
     * [packageFqName], in which case the reference resolves without an import.
     *
     * A callee is wrapped in a [KtCallExpression] (`a.Helper(1)`), so the qualified access is found
     * through that call node rather than directly through the callee's parent. A type reference is
     * wrapped in a [KtUserType] whose qualifier spells the receiver (`a.Helper`).
     */
    private fun KtSimpleNameExpression.isPackageQualified(packageFqName: FqName): Boolean {
        val selector = (parent as? KtCallExpression)?.takeIf { it.calleeExpression == this } ?: this
        val receiverText = when (val qualified = selector.parent as? KtDotQualifiedExpression) {
            null -> {
                val userType = parent as? KtUserType
                if (userType == null || userType.referenceExpression != this) return false
                userType.qualifier?.text ?: return false
            }
            else -> {
                if (qualified.selectorExpression != selector) return false
                qualified.receiverExpression.text
            }
        }
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
     * Only real PSI import directives and the package directive are consulted, so text inside
     * literals or comments cannot influence either the duplicate check or the boundary.
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
        val declarationStart = file.declarations.firstOrNull()?.textRange?.startOffset ?: return null
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
     * declarations that stay behind. References themselves are captured separately from
     * K2-resolved usages.
     *
     * @param participants files whose text may change
     * @param movedDeclarations old top-level declaration FQNs mapped to their new FQNs
     * @return per-file replacements keyed by captured source offsets
     */
    private fun captureImportEdits(
        participants: Collection<KtFile>,
        movedDeclarations: Map<FqName, FqName>,
        movedClassFqNames: Set<FqName>,
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
                val replacement = if (directive.isAllUnder) {
                    // A package wildcard keeps importing whatever stays behind, so only a
                    // wildcard rooted in a moved class (or a class nested inside it) follows
                    // that class to its new package, keeping the nested suffix and `.*`.
                    val imported = directive.importedFqName ?: return@forEach
                    val movedClass = longestMovedClassPrefix(imported, movedClassFqNames) ?: return@forEach
                    val newPrefix = movedDeclarations[movedClass] ?: return@forEach
                    val suffixSegments = imported.pathSegments().drop(movedClass.pathSegments().size)
                    val suffixText = if (suffixSegments.isEmpty()) {
                        ""
                    } else {
                        suffixSegments.joinToString(separator = ".", prefix = ".") { segment ->
                            KotlinSourceNames.renderIdentifier(segment.asString())
                        }
                    }
                    KotlinSourceNames.renderFqName(newPrefix) + suffixText
                } else {
                    rewriteImportedPath(directive.identifiersWithin(pathStart, pathEnd), mappings)
                        ?: return@forEach
                }
                edits.getOrPut(file, ::linkedSetOf) += TextEdit(pathStart, pathEnd, replacement)
            }
        }
        return edits.mapValues { (_, fileEdits) -> fileEdits.toList() }
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
     * @param segments identifier names of the imported path, in source order
     * @param mappings moved declarations ordered from the longest FQN to the shortest
     * @return retargeted path text, or `null` when the import does not target a moved declaration
     */
    private fun rewriteImportedPath(
        segments: List<String>,
        mappings: List<Map.Entry<FqName, FqName>>,
    ): String? {
        mappings.forEach { (oldFqName, newFqName) ->
            val oldSegments = oldFqName.pathSegments().map { segment -> segment.asString() }
            if (segments.size < oldSegments.size) return@forEach
            if (segments.take(oldSegments.size) != oldSegments) return@forEach
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
