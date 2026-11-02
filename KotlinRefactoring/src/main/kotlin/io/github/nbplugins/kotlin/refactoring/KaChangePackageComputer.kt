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

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.k2.refactoring.move.MoveImport
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2ChangePackageDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.K2ChangePackageRefactoringProcessor
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallExpression
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
    private companion object {
        /** Matches a file-level Kotlin package directive in source text. */
        val PACKAGE_DIRECTIVE = Regex("(?m)^package\\s+[^\\s;]+")

        /** Matches a non-aliased Kotlin import directive in source text. */
        val IMPORT_DIRECTIVE = Regex("(?m)^import\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*$")
    }

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
            val samePackageImportUpdates = if (updateReferences) {
                // Incoming imports fix callers that lose visibility of a moved declaration, either
                // because they shared its package or because they imported the whole old package.
                // Outgoing imports fix the moved file itself losing visibility of the declarations
                // that stay behind in its former package.
                val incomingImports = findIncomingImportUpdates(files, targets)
                val outgoingImports = findOutgoingSamePackageImports(files, targets)
                (incomingImports.keys + outgoingImports.keys).associateWith { file ->
                    incomingImports[file].orEmpty() + outgoingImports[file].orEmpty()
                }
            } else {
                emptyMap()
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
            val participants = (files + filesWithUsages(usages) + importOnlyUsageFiles + samePackageImportUpdates.keys)
                .distinct()
            // Capture replacement ranges while PSI is still valid. Only usages that K2 resolved to
            // a moved declaration and direct import directives are retargeted, so text that merely
            // spells a moved FQN (comments, strings, unrelated receiver chains) is left alone.
            val textEdits = if (updateReferences) {
                captureTextEdits(participants, movedDeclarations, usages)
            } else {
                emptyMap()
            }
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
                val text = targets[file]?.let { target -> ensurePackageDirectiveSeparator(file.text, target) } ?: file.text
                // Captured offsets refer to the original text; the package directive is the only
                // text before them that changed, so a single length delta realigns all edits.
                val retargetedText = applyTextEdits(text, textEdits[file].orEmpty(), text.length - originalText.length)
                changedFiles[path] = addSamePackageImports(retargetedText, samePackageImportUpdates[file].orEmpty())
            }
            ApplyOutcome.Success(changedFiles, originalTexts)
        } catch (error: Throwable) {
            ApplyOutcome.Error(error)
        }
    }

    /**
     * Finds callers that will lose visibility of a moved declaration and need an explicit import.
     *
     * A caller loses that visibility in two ways: it formerly shared the declaration's package, or
     * it imported the whole old package with a wildcard. A direct import is rewritten elsewhere and
     * keeps the binding, and a caller that ends in the declaration's final package keeps the
     * implicit binding. Text-based retargeting cannot invent an import, so these callers are
     * discovered from resolved references before any directive changes.
     *
     * Every moved declaration is searched directly rather than through the processor's usages:
     * the processor only reports functions, properties, and classes, while a moved type alias also
     * loses its implicit same-package binding.
     *
     * @param movedFiles Kotlin sources whose declared package changes
     * @param targets mapped files with their final declared packages
     * @return affected files mapped to the imports their references now require
     */
    private fun findIncomingImportUpdates(
        movedFiles: Collection<KtFile>,
        targets: Map<KtFile, FqName>,
    ): Map<KtFile, Set<FqName>> {
        val movedDeclarations = movedFiles.flatMap { file ->
            file.declarations.filterIsInstance<KtNamedDeclaration>().mapNotNull { declaration ->
                declaration.name?.let { name -> declaration to targets.getValue(file).child(Name.identifier(name)) }
            }
        }.toMap()
        if (movedDeclarations.isEmpty()) return emptyMap()
        val session = KotlinMoveUsageSearchService.getInstance() ?: return emptyMap()
        val updates = linkedMapOf<KtFile, MutableSet<FqName>>()
        movedDeclarations.forEach { (declaration, targetFqName) ->
            val name = declaration.name ?: return@forEach
            val declarationFile = declaration.containingKtFile
            val originalPackage = declarationFile.packageFqName
            val oldFqName = originalPackage.child(Name.identifier(name))
            session.findUsages(declaration).forEach { reference ->
                val file = reference.element?.containingFile as? KtFile ?: return@forEach
                if (file.virtualFile?.path == declarationFile.virtualFile?.path) return@forEach
                val finalPackage = targets[file] ?: file.packageFqName
                if (finalPackage == targetFqName.parent()) return@forEach
                val importsDirectly = file.importDirectives.any { directive ->
                    !directive.isAllUnder && directive.importedFqName == oldFqName
                }
                if (importsDirectly) return@forEach
                val sharesOriginalPackage = file.packageFqName == originalPackage
                val importsOriginalPackage = file.importDirectives.any { directive ->
                    directive.isAllUnder && directive.importedFqName == originalPackage
                }
                if (!sharesOriginalPackage && !importsOriginalPackage) return@forEach
                updates.getOrPut(file, ::linkedSetOf) += targetFqName
            }
        }
        return updates
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
            if (originalPackage.isRoot || targets[file] == originalPackage) return@forEach
            val importedFqNames = file.importDirectives.mapNotNull { it.importedFqName }.toSet()
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
                            val declaration = reference.resolveToSymbol()?.psi as? KtNamedDeclaration ?: return
                            val containingFile = declaration.containingKtFile
                            if (containingFile.virtualFile?.path in movedPaths) return
                            if (containingFile.packageFqName != originalPackage) return
                            if (declaration.parent !is KtFile) return
                            val name = declaration.name ?: return
                            val importFqName = originalPackage.child(Name.identifier(name))
                            if (importFqName in importedFqNames || originalPackage in starPackages) return
                            if (expression.isPackageQualified(originalPackage)) return
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
     * Tells whether [this] is the selector of a qualified access whose receiver already spells out
     * [packageFqName], in which case the reference resolves without an import.
     */
    private fun KtSimpleNameExpression.isPackageQualified(packageFqName: FqName): Boolean {
        val qualified = parent as? KtDotQualifiedExpression ?: return false
        if (qualified.selectorExpression != this) return false
        val receiverText = qualified.receiverExpression.text
        val packageName = packageFqName.asString()
        return packageName == receiverText || packageName.startsWith("$receiverText.")
    }

    /**
     * Separates a package directive from the first declaration of a source that had none.
     *
     * [K2ChangePackageRefactoringProcessor.updatePackages] replaces the empty directive of a
     * root-package file in place, which leaves `package dest` glued to the next declaration in
     * standalone PSI. Restore the mandatory line break before references are rewritten.
     *
     * @return text whose package directive is followed by whitespace
     */
    private fun ensurePackageDirectiveSeparator(text: String, targetPackage: FqName): String {
        if (targetPackage.isRoot) return text
        val directive = "package ${targetPackage.asString()}"
        if (!text.startsWith(directive)) return text
        val next = text.getOrNull(directive.length) ?: return text
        return if (next.isWhitespace()) text else "$directive\n\n${text.substring(directive.length)}"
    }

    /**
     * Adds imports that no longer arrive implicitly from the package or a wildcard import.
     *
     * The header is rebuilt as text rather than through PSI: the import list is invalid after the
     * package directive changed, and the standalone parser drops inserted whitespace nodes.
     *
     * @return final source text with a canonical package/import boundary
     */
    private fun addSamePackageImports(usageText: String, imports: Set<FqName>): String {
        if (imports.isEmpty()) return usageText
        val packageMatch = PACKAGE_DIRECTIVE.find(usageText) ?: return usageText
        val existingFqNames = IMPORT_DIRECTIVE.findAll(usageText)
            .map { match -> FqName(match.groupValues[1]) }
            .toSet()
        val missingImports = imports.filterNot { it in existingFqNames }
            .map { imported -> "import ${imported.asString()}" }
        if (missingImports.isEmpty()) return usageText
        val firstImport = IMPORT_DIRECTIVE.find(usageText)
        return if (firstImport == null) {
            usageText.substring(0, packageMatch.range.last + 1) +
                "\n\n${missingImports.joinToString(separator = "\n")}" +
                usageText.substring(packageMatch.range.last + 1)
        } else {
            usageText.substring(0, firstImport.range.first) +
                "${missingImports.joinToString(separator = "\n")}\n" +
                usageText.substring(firstImport.range.first)
        }
    }

    /** Captures usage files before K2 reference rebinding can invalidate individual usage elements. */
    private fun filesWithUsages(usages: Collection<UsageInfo>): List<KtFile> =
        usages.mapNotNull { usage -> usage.element?.containingFile as? KtFile }.distinct()

    /** One replacement of `[start, end)` in the source text captured before package mutation. */
    private data class TextEdit(val start: Int, val end: Int, val replacement: String)

    /**
     * Captures the exact text ranges that must follow moved declarations.
     *
     * Only two kinds of text are captured: direct import directives and reference chains that K2
     * resolved to a moved declaration before mutation. Everything else — comments, string literals,
     * declaration names, and receiver chains that merely spell the same text — is left alone.
     *
     * @param participants files whose text may change
     * @param movedDeclarations old top-level declaration FQNs mapped to their new FQNs
     * @param usages external usages resolved before package mutation
     * @return per-file replacements keyed by captured source offsets
     */
    private fun captureTextEdits(
        participants: Collection<KtFile>,
        movedDeclarations: Map<FqName, FqName>,
        usages: Collection<UsageInfo>,
    ): Map<KtFile, List<TextEdit>> {
        if (movedDeclarations.isEmpty()) return emptyMap()
        val mappings = movedDeclarations.entries
            .sortedByDescending { (oldFqName, _) -> oldFqName.pathSegments().size }
        val edits = linkedMapOf<KtFile, MutableSet<TextEdit>>()
        participants.forEach { file ->
            file.importDirectives.forEach { directive ->
                if (directive.isAllUnder) return@forEach
                val importedText = directive.importPath?.pathStr ?: return@forEach
                val replacement = rewriteImportedPath(importedText, mappings) ?: return@forEach
                val offset = directive.textRange.startOffset + directive.text.indexOf(importedText)
                edits.getOrPut(file, ::linkedSetOf) +=
                    TextEdit(offset, offset + importedText.length, replacement)
            }
        }
        usages.filterIsInstance<MoveRenameUsageInfo>().forEach { usage ->
            val element = usage.element as? KtElement ?: return@forEach
            val file = element.containingFile as? KtFile ?: return@forEach
            val declaration = usage.referencedElement as? KtNamedDeclaration ?: return@forEach
            val name = declaration.name ?: return@forEach
            val oldFqName = declaration.containingKtFile.packageFqName.child(Name.identifier(name))
            val newFqName = movedDeclarations[oldFqName] ?: return@forEach
            // A root-package FQN is a bare name. Qualifying such a reference would rewrite
            // declaration-like positions that cannot take a package prefix (`::moved`); imports
            // alone keep root-package bindings intact.
            if (oldFqName.parent().isRoot) return@forEach
            val chain = element.qualifiedChain()
            if (chain.text != oldFqName.asString()) return@forEach
            edits.getOrPut(file, ::linkedSetOf) += TextEdit(
                chain.startOffset,
                chain.endOffset,
                newFqName.asString(),
            )
        }
        return edits.mapValues { (_, fileEdits) -> fileEdits.toList() }
    }

    /**
     * Rewrites the package prefix of an imported path, keeping alias and nested-class suffixes.
     *
     * Import paths are compared segment by segment rather than as token sequences so a backticked
     * segment still matches its plain FQN.
     *
     * @param importedText path text as written in the directive
     * @param mappings moved declarations ordered from the longest FQN to the shortest
     * @return retargeted path text, or `null` when the import does not target a moved declaration
     */
    private fun rewriteImportedPath(
        importedText: String,
        mappings: List<Map.Entry<FqName, FqName>>,
    ): String? {
        val segments = importedText.split('.')
        mappings.forEach { (oldFqName, newFqName) ->
            val oldSegments = oldFqName.pathSegments().map { segment -> segment.asString() }
            if (segments.size < oldSegments.size) return@forEach
            val prefix = segments.take(oldSegments.size).map { segment -> segment.removeSurrounding("`") }
            if (prefix != oldSegments) return@forEach
            val suffix = segments.drop(oldSegments.size)
            val separator = if (suffix.isEmpty()) "" else "."
            return newFqName.asString() + suffix.joinToString(separator = ".", prefix = separator)
        }
        return null
    }

    /** Source range and text of the qualified chain that ends at a reference expression. */
    private data class QualifiedChain(val startOffset: Int, val endOffset: Int, val text: String)

    /**
     * Extends a reference expression through the qualified receiver chain that contains it.
     *
     * `old.pkg.moved()` visits the `moved` reference; climbing through qualified expressions, their
     * call nodes, and user types yields the `old.pkg.moved` text while dropping any argument list
     * or type argument suffix. The chain stops at any other construct.
     */
    private fun KtElement.qualifiedChain(): QualifiedChain {
        var current: KtElement = this
        var start = textRange.startOffset
        val end = textRange.endOffset
        while (true) {
            val parent = current.parent
            when {
                parent is KtDotQualifiedExpression && parent.selectorExpression == current -> {
                    start = parent.receiverExpression.textRange.startOffset
                    current = parent
                }
                parent is KtCallExpression && parent.calleeExpression == current -> current = parent
                parent is KtUserType && parent.referenceExpression == current -> {
                    parent.qualifier?.let { qualifier -> start = qualifier.textRange.startOffset }
                    current = parent
                }
                else -> return QualifiedChain(start, end, containingFile.text.substring(start, end))
            }
        }
    }

    /**
     * Applies replacements captured on the pre-mutation text to the post-mutation text.
     *
     * The package directive is the only text before the captured ranges that changes, so one length
     * delta realigns every offset. Nested ranges are dropped in favour of the widest captured
     * replacement, and edits are applied from the end so earlier offsets stay valid.
     *
     * @param text source text after package mutation
     * @param edits replacements captured on the original text
     * @param shift length difference between the mutated and original text
     * @return text with all non-overlapping replacements applied
     */
    private fun applyTextEdits(text: String, edits: Collection<TextEdit>, shift: Int): String {
        if (edits.isEmpty()) return text
        val shifted = edits.asSequence()
            .map { edit -> TextEdit(edit.start + shift, edit.end + shift, edit.replacement) }
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
        return result.toString()
    }
}
