/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import java.util.logging.Logger

/**
 * Computes all [TextChange]s required to rename a Kotlin symbol across the project.
 *
 * Ported from IntelliJ K2 `K2RenameRefactoringSupport` (override cascade) and
 * `KotlinK2FindUsagesSupport` (reference search). Has no NetBeans dependencies;
 * all I/O is through [KtFile] objects provided by the caller.
 *
 * For each rename operation, this class:
 * 1. Resolves the symbol at the cursor to its PSI declaration.
 * 2. Expands the rename target set to include all overriding and overridden declarations
 *    (override cascade): a super-method and all its implementations are renamed together.
 * 3. For each target declaration, adds a [TextChange] for the declaration name identifier.
 * 4. For each target declaration, finds all reference expressions across [projectKtFiles] and
 *    adds a [TextChange] for each reference.
 *
 * Results are keyed by the virtual-file path of each source file (accessible via
 * `KtFile.virtualFile.path`), which the caller maps back to NetBeans [org.openide.filesystems.FileObject]s.
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 *
 * @param cursorKtFile      the K2-session KtFile at the cursor (must be part of [projectKtFiles])
 * @param offset            character offset of the cursor within [cursorKtFile]
 * @param projectKtFiles    all KtFiles in the project to search for usages
 * @param newName           the replacement identifier name entered by the user
 * @param searchInComments  when `true`, also rename literal occurrences of the old name inside
 *                          single-line (`//`), block (`/* */`), and KDoc (`/** */`) comments
 */
class KaRenameComputer(
    private val cursorKtFile: KtFile,
    private val offset: Int,
    private val projectKtFiles: Set<KtFile>,
    val newName: String,
    private val searchInComments: Boolean = false,
) {

    private companion object {
        private val LOG = Logger.getLogger(KaRenameComputer::class.java.name)
    }

    /**
     * Returns the current name of the symbol at the cursor, or `null` if not resolvable.
     *
     * Used to pre-fill the rename dialog and as the [TextChange.oldText] for undo.
     */
    fun resolveOldName(): String? = runCatching {
        val element = cursorKtFile.findElementAt(offset) ?: return null
        PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java)?.name
    }.getOrNull()

    /**
     * Computes all text replacements needed to rename the symbol at the cursor.
     *
     * @return a map from virtual-file path to an ordered, deduplicated list of [TextChange]s,
     *         or `null` if the symbol cannot be resolved
     */
    fun compute(): Map<String, List<TextChange>>? {
        val targetPsi = resolveTargetPsi() ?: return null
        val oldName = resolveOldName() ?: return null

        val allTargets = mutableSetOf(targetPsi)
        collectOverrideTargets(targetPsi, allTargets)

        val result = mutableMapOf<String, MutableList<TextChange>>()

        for (target in allTargets) {
            addDeclarationNameChange(target, oldName, result)

            for ((path, ranges) in searchAllFilesForTarget(target)) {
                val kaFile = projectKtFiles.firstOrNull { it.virtualFile?.path == path } ?: continue
                val changes = ranges.map { (start, end) ->
                    val rangeOldText = runCatching {
                        kaFile.text.substring(start, end)
                    }.getOrElse { oldName }
                    TextChange(start, end, newName, rangeOldText)
                }
                result.getOrPut(path) { mutableListOf() }.addAll(changes)
            }
        }

        if (searchInComments) {
            for (kaFile in projectKtFiles) {
                val path = kaFile.virtualFile?.path ?: continue
                val commentChanges = searchCommentsInFile(kaFile, oldName)
                if (commentChanges.isNotEmpty()) {
                    result.getOrPut(path) { mutableListOf() }.addAll(commentChanges)
                }
            }
        }

        return result.mapValues { (_, changes) ->
            changes.distinctBy { it.start to it.end }.sortedBy { it.start }
        }
    }

    /**
     * Scans [kaFile] for occurrences of [oldName] inside comment tokens (single-line, block,
     * and KDoc) and returns a [TextChange] for each occurrence.
     *
     * Does a plain-text substring search within each comment token; does not require
     * whole-word boundaries (consistent with Java rename "search in comments" behaviour).
     */
    private fun searchCommentsInFile(kaFile: KtFile, oldName: String): List<TextChange> {
        val changes = mutableListOf<TextChange>()
        val commentTypes = setOf(
            KtTokens.EOL_COMMENT,
            KtTokens.BLOCK_COMMENT,
            KtTokens.DOC_COMMENT,
            KDocTokens.TEXT,
        )
        kaFile.accept(object : KtTreeVisitorVoid() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                super.visitElement(element)
                if (element.node?.elementType !in commentTypes) return
                val text = element.text
                var idx = 0
                while (true) {
                    idx = text.indexOf(oldName, idx)
                    if (idx < 0) break
                    val start = element.textRange.startOffset + idx
                    changes.add(TextChange(start, start + oldName.length, newName, oldName))
                    idx += oldName.length
                }
            }
        })
        return changes
    }

    /**
     * Resolves the symbol at [offset] in [cursorKtFile] to a PSI declaration element.
     *
     * - If the cursor is on a reference expression, the reference is resolved to its declaration.
     * - If the cursor is on the name of a named declaration, the declaration element is returned.
     * - Returns `null` when the symbol cannot be resolved.
     */
    fun resolveTargetPsi(): PsiElement? = runCatching {
        val element = cursorKtFile.findElementAt(offset) ?: return null

        val refExpr = PsiTreeUtil.getNonStrictParentOfType(element, KtReferenceExpression::class.java)
        if (refExpr != null) {
            val resolved = analyze(cursorKtFile) {
                refExpr.mainReference?.resolveToSymbol()?.psi
            }
            if (resolved != null) return resolved
        }

        PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java)
    }.getOrElse { e ->
        LOG.severe("KaRenameComputer: resolveTargetPsi failed: $e")
        null
    }

    /** Adds a [TextChange] for the name identifier of [targetDecl] to [into]. */
    private fun addDeclarationNameChange(
        targetDecl: PsiElement,
        oldName: String,
        into: MutableMap<String, MutableList<TextChange>>,
    ) {
        val namedDecl = targetDecl as? KtNamedDeclaration ?: return
        val nameId = namedDecl.nameIdentifier ?: return
        val path = namedDecl.containingFile?.virtualFile?.path ?: return
        val range = nameId.textRange
        val actualOldName = nameId.text.takeIf { it.isNotEmpty() } ?: oldName
        into.getOrPut(path) { mutableListOf() }
            .add(TextChange(range.startOffset, range.endOffset, newName, actualOldName))
    }

    /**
     * Expands [targets] with override-related declarations.
     *
     * Ported from `K2RenameRefactoringSupport.getAllOverridenFunctions()`:
     * - Supers via [KaCallableSymbol.allOverriddenSymbols]
     * - Subs via a PSI visitor over [projectKtFiles]
     */
    private fun collectOverrideTargets(targetPsi: PsiElement, targets: MutableSet<PsiElement>) {
        if (targetPsi !is KtNamedFunction && targetPsi !is KtProperty) return

        runCatching {
            analyze(cursorKtFile) {
                val callableSymbol = when (targetPsi) {
                    is KtNamedFunction -> targetPsi.symbol as? KaCallableSymbol
                    is KtProperty -> targetPsi.symbol as? KaCallableSymbol
                    else -> null
                } ?: return@analyze

                for (overridden in callableSymbol.allOverriddenSymbols) {
                    overridden.psi?.let { if (it !in targets) targets.add(it) }
                }
            }
        }.onFailure { e -> LOG.warning("KaRenameComputer: super-override scan failed: $e") }

        for (kaFile in projectKtFiles) {
            scanForSubOverrides(kaFile, targetPsi, targets)
        }
    }

    /** Scans [kaFile] for declarations that override [targetPsi] and adds them to [targets]. */
    private fun scanForSubOverrides(
        kaFile: KtFile,
        targetPsi: PsiElement,
        targets: MutableSet<PsiElement>,
    ) {
        runCatching {
            analyze(kaFile) {
                kaFile.accept(object : KtTreeVisitorVoid() {
                    override fun visitNamedFunction(function: KtNamedFunction) {
                        super.visitNamedFunction(function)
                        if (!function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
                        if (function in targets) return
                        val sym = runCatching { function.symbol as? KaCallableSymbol }
                            .getOrNull() ?: return
                        if (sym.allOverriddenSymbols.any { it.psi == targetPsi }) {
                            targets.add(function)
                        }
                    }

                    override fun visitProperty(property: KtProperty) {
                        super.visitProperty(property)
                        if (!property.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
                        if (property in targets) return
                        val sym = runCatching { property.symbol as? KaCallableSymbol }
                            .getOrNull() ?: return
                        if (sym.allOverriddenSymbols.any { it.psi == targetPsi }) {
                            targets.add(property)
                        }
                    }
                })
            }
        }.onFailure { /* ignore per-file failures */ }
    }

    /**
     * Searches all [projectKtFiles] for references to [targetPsi].
     *
     * @return map from file path to list of (startOffset, endOffset) pairs
     */
    private fun searchAllFilesForTarget(targetPsi: PsiElement): Map<String, List<Pair<Int, Int>>> {
        val result = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        for (kaFile in projectKtFiles) {
            val path = kaFile.virtualFile?.path ?: continue
            val ranges = searchFileForUsages(kaFile, targetPsi)
            if (ranges.isNotEmpty()) {
                result.getOrPut(path) { mutableListOf() }.addAll(ranges)
            }
        }
        return result
    }

    /** Returns all reference expression ranges in [kaFile] that resolve to [targetPsi]. */
    private fun searchFileForUsages(kaFile: KtFile, targetPsi: PsiElement): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        runCatching {
            analyze(kaFile) {
                kaFile.accept(object : KtTreeVisitorVoid() {
                    override fun visitReferenceExpression(expression: KtReferenceExpression) {
                        super.visitReferenceExpression(expression)
                        val sym = runCatching {
                            expression.mainReference?.resolveToSymbol()
                        }.getOrNull() ?: return
                        if (sym.psi == targetPsi) {
                            val tr = expression.textRange
                            ranges.add(tr.startOffset to tr.endOffset)
                        }
                    }
                })
            }
        }.onFailure { e -> LOG.warning("KaRenameComputer: search failed in ${kaFile.name}: $e") }
        return ranges
    }
}
