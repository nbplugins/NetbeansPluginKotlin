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
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

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
            // Use the copied upstream Change Package processor rather than the F4.1 Move File helper:
            // its findUsages() also reports whole-file internal references, so a same-package caller
            // without any import is still known and gets an explicit import once the package changes.
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
                // Incoming imports fix callers that lose visibility of a moved declaration;
                // outgoing imports fix the moved file itself losing visibility of the declarations
                // that stay behind in its former package.
                val incomingImports = findSamePackageImportUpdates(files, targets)
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
            // A root-package declaration's FQN is its bare name: replacing it textually would also
            // rewrite the declaration itself (`fun moved` -> `fun dest.moved`). Root-package
            // references are never package-qualified, so the imports above keep them bound.
            val retargetableDeclarations = movedDeclarations.filterKeys { oldFqName -> !oldFqName.parent().isRoot }
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
                val text = targets[file]?.let { target -> ensurePackageDirectiveSeparator(file.text, target) } ?: file.text
                val changedText = rewriteMovedReferences(
                    text,
                    retargetableDeclarations,
                    samePackageImportUpdates[file].orEmpty(),
                )
                file.virtualFile?.path?.let { changedFiles[it] = changedText }
            }
            ApplyOutcome.Success(changedFiles, originalTexts)
        } catch (error: Throwable) {
            ApplyOutcome.Error(error)
        }
    }

    /**
     * Finds old-package simple references that will lose their implicit package visibility.
     *
     * Text-based retargeting can rewrite an existing import but cannot invent one, so discover the
     * affected callers before directives change and later add only the imports they need.
     */
    private fun findSamePackageImportUpdates(
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
            session.findUsages(declaration).forEach { reference ->
                val file = reference.element?.containingFile as? KtFile ?: return@forEach
                val originalPackage = declaration.containingKtFile.packageFqName
                val finalPackage = targets[file]
                if (file.packageFqName != originalPackage || finalPackage == targetFqName.parent()) return@forEach
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
     * Adds imports for former same-package declarations that no longer resolve implicitly.
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

    /**
     * Rewrites imports and fully qualified Kotlin references without touching invalid standalone PSI.
     *
     * [K2ChangePackageRefactoringProcessor.updatePackages] invalidates every standalone PSI smart
     * pointer. Source text has already been captured in [originalTexts] at that point, so update
     * only FQN token sequences and import directives here; ordinary simple references continue to
     * resolve through their rewritten imports.
     *
     * @param usageText source text read after the package directive changed
     * @param movedDeclarations old declaration FQNs mapped to their new FQNs
     * @param samePackageImports explicit imports needed by callers that formerly shared a package
     * @return source text that preserves supported Kotlin bindings after the package change
     */
    private fun rewriteMovedReferences(
        usageText: String,
        movedDeclarations: Map<FqName, FqName>,
        samePackageImports: Set<FqName>,
    ): String {
        val retargetedText = if (movedDeclarations.isEmpty()) {
            usageText
        } else {
            usageText.replaceFqNames(movedDeclarations)
        }
        return addSamePackageImports(retargetedText, samePackageImports)
    }

    /** Lexical context of the source scanner; only code contexts allow FQN replacement. */
    private sealed class ScanMode {
        /** Ordinary Kotlin code, including package and import directives. */
        object Code : ScanMode()

        /** Executable `${...}` expression inside a string literal, where code resumes. */
        class Template(var braceDepth: Int) : ScanMode()

        /** Double-quoted string literal, where only a `${...}` template re-enters code. */
        object StringLiteral : ScanMode()

        /** Triple-quoted raw string, where only a `${...}` template re-enters code. */
        object RawString : ScanMode()

        /** Character literal, whose content is never a reference. */
        object CharacterLiteral : ScanMode()

        /** Block comment; Kotlin block comments nest. */
        class BlockComment(var depth: Int) : ScanMode()
    }

    /**
     * Replaces the longest matching declaration FQN at each code position.
     *
     * Comments, character literals, string literals, and raw strings are copied verbatim, except
     * that executable `${...}` template expressions re-enter code mode: a reference such as
     * `"${old.pkg.moved()}"` is code, not the string-text occurrence excluded by the refactoring.
     * Longest match wins so that a qualified access such as `a.b.C.member` keeps its suffix even
     * when both `a.b.C` and a shorter prefix are moved declarations. The package directive is
     * never rewritten: it can share its text with a declaration's FQN.
     */
    private fun String.replaceFqNames(mappings: Map<FqName, FqName>): String {
        val candidates = mappings.entries
            .map { (oldFqName, newFqName) -> oldFqName.asString() to newFqName.asString() }
            .sortedByDescending { (oldFqName, _) -> oldFqName.length }
        val packageRange = PACKAGE_DIRECTIVE.find(this)?.range
        val result = StringBuilder(length)
        val modes = ArrayDeque<ScanMode>()
        modes.addLast(ScanMode.Code)
        var index = 0
        while (index < length) {
            val current = this[index]
            val next = getOrNull(index + 1)
            when (val mode = modes.last()) {
                ScanMode.Code, is ScanMode.Template -> {
                    val template = mode as? ScanMode.Template
                    when {
                        current == '/' && next == '/' -> {
                            val lineEnd = indexOf('\n', index).let { if (it < 0) length else it }
                            result.append(this, index, lineEnd)
                            index = lineEnd
                        }
                        current == '/' && next == '*' -> {
                            result.append(current).append(next)
                            index += 2
                            modes.addLast(ScanMode.BlockComment(1))
                        }
                        current == '"' && getOrNull(index + 1) == '"' && getOrNull(index + 2) == '"' -> {
                            result.append("\"\"\"")
                            index += 3
                            modes.addLast(ScanMode.RawString)
                        }
                        current == '"' -> {
                            result.append(current)
                            index++
                            modes.addLast(ScanMode.StringLiteral)
                        }
                        current == '\'' -> {
                            result.append(current)
                            index++
                            modes.addLast(ScanMode.CharacterLiteral)
                        }
                        template != null && current == '{' -> {
                            result.append(current)
                            index++
                            template.braceDepth++
                        }
                        template != null && current == '}' -> {
                            result.append(current)
                            index++
                            if (--template.braceDepth <= 0) modes.removeLast()
                        }
                        else -> {
                            val match = if (packageRange != null && index in packageRange) {
                                null
                            } else {
                                candidates.firstOrNull { (oldFqName, _) ->
                                    startsWith(oldFqName, index) &&
                                        canStartFqNameAt(index) &&
                                        canEndFqNameAt(index + oldFqName.length)
                                }
                            }
                            if (match == null) {
                                result.append(current)
                                index++
                            } else {
                                result.append(match.second)
                                index += match.first.length
                            }
                        }
                    }
                }
                ScanMode.StringLiteral -> when {
                    current == '\\' -> {
                        result.append(current)
                        if (next != null) {
                            result.append(next)
                            index += 2
                        } else {
                            index++
                        }
                    }
                    current == '$' && next == '{' -> {
                        result.append(current).append(next)
                        index += 2
                        modes.addLast(ScanMode.Template(1))
                    }
                    else -> {
                        result.append(current)
                        index++
                        if (current == '"') modes.removeLast()
                    }
                }
                ScanMode.RawString -> when {
                    current == '$' && next == '{' -> {
                        result.append(current).append(next)
                        index += 2
                        modes.addLast(ScanMode.Template(1))
                    }
                    current == '"' && getOrNull(index + 1) == '"' && getOrNull(index + 2) == '"' -> {
                        result.append("\"\"\"")
                        index += 3
                        modes.removeLast()
                    }
                    else -> {
                        result.append(current)
                        index++
                    }
                }
                ScanMode.CharacterLiteral -> when {
                    current == '\\' -> {
                        result.append(current)
                        if (next != null) {
                            result.append(next)
                            index += 2
                        } else {
                            index++
                        }
                    }
                    else -> {
                        result.append(current)
                        index++
                        if (current == '\'') modes.removeLast()
                    }
                }
                is ScanMode.BlockComment -> when {
                    current == '/' && next == '*' -> {
                        result.append(current).append(next)
                        index += 2
                        mode.depth++
                    }
                    current == '*' && next == '/' -> {
                        result.append(current).append(next)
                        index += 2
                        if (--mode.depth <= 0) modes.removeLast()
                    }
                    else -> {
                        result.append(current)
                        index++
                    }
                }
            }
        }
        return result.toString()
    }

    /** Rejects a match that continues a longer identifier or qualified name on its left. */
    private fun String.canStartFqNameAt(index: Int): Boolean {
        val previous = index - 1
        return previous !in indices || (!this[previous].isLetterOrDigit() && this[previous] != '_' && this[previous] != '.')
    }

    /** Rejects a match that continues a longer identifier on its right; `.member` suffixes are kept. */
    private fun String.canEndFqNameAt(index: Int): Boolean =
        index !in indices || (!this[index].isLetterOrDigit() && this[index] != '_')
}
