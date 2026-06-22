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
package io.github.nbplugins.kotlin.nbm.navigation

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.netbeans.modules.csl.api.OffsetRange
import org.openide.filesystems.FileObject

/**
 * Computes all usages of a Kotlin symbol across the project using the K2 Analysis API.
 *
 * Given a [KotlinAnalysisAPISession], a K2-session [org.jetbrains.kotlin.psi.KtFile] at the
 * cursor position, and a cursor [offset], this class:
 * 1. Resolves the symbol at the cursor to its PSI declaration element ([targetPsi]).
 * 2. Iterates every [KtFile][org.jetbrains.kotlin.psi.KtFile] in [searchFiles].
 * 3. For each file, runs an [analyze] block and visits all
 *    [KtReferenceExpression] nodes, collecting those that resolve to [targetPsi].
 *
 * **Symbol identity** — K2 symbols are session-scoped. Across different `analyze {}` blocks,
 * the same declaration is reachable as the identical [PsiElement] because
 * [KotlinAnalysisAPISession] stores a single [org.jetbrains.kotlin.psi.KtFile] instance per
 * source file (loaded once into [KaModule][org.jetbrains.kotlin.analysis.api.projectStructure.KaModule]
 * during session initialisation). Comparing [PsiElement] object identity (`sym.psi == targetPsi`)
 * is therefore reliable within a session.
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 *
 * @param cursorKtFile the K2-session KtFile containing the cursor (must be owned by [session])
 * @param offset cursor offset within [cursorKtFile]
 * @param session the active [KotlinAnalysisAPISession] for the project
 * @param searchFiles set of [FileObject]s to search for usages (typically all project source files)
 */
class KaFindUsagesComputer(
    private val cursorKtFile: org.jetbrains.kotlin.psi.KtFile,
    private val offset: Int,
    private val session: KotlinAnalysisAPISession,
    private val searchFiles: Set<FileObject>
) {

    /**
     * Runs the find-usages search.
     *
     * @return a map from [FileObject] to the list of [OffsetRange]s within that file that
     *         reference the symbol at the cursor; empty if the cursor is not on a resolvable symbol
     */
    fun compute(): Map<FileObject, List<OffsetRange>> {
        val targetPsi = resolveTargetPsi() ?: return emptyMap()
        val result = mutableMapOf<FileObject, MutableList<OffsetRange>>()
        for (fo in searchFiles) {
            val kaFile = session.getKtFileForPath(fo.path) ?: continue
            val ranges = searchFileForUsages(kaFile, targetPsi)
            if (ranges.isNotEmpty()) {
                result.getOrPut(fo) { mutableListOf() }.addAll(ranges)
            }
        }
        return result
    }

    /**
     * Resolves the symbol at [offset] in [cursorKtFile] to a PSI declaration element.
     *
     * - If the cursor is on a reference expression, the reference is resolved to its declaration.
     * - If the cursor is on the name of a named declaration (function, class, property, etc.),
     *   the declaration element itself is returned.
     * - Returns `null` when the symbol cannot be resolved or the element at the cursor is
     *   not a resolvable Kotlin name.
     *
     * @return the PSI declaration element, or `null`
     */
    fun resolveTargetPsi(): PsiElement? = runCatching {
        val element = cursorKtFile.findElementAt(offset) ?: return null

        // Case 1: cursor on a reference expression → resolve to its declaration
        val refExpr = PsiTreeUtil.getNonStrictParentOfType(element, KtReferenceExpression::class.java)
        if (refExpr != null) {
            val resolved = analyze(cursorKtFile) {
                refExpr.mainReference?.resolveToSymbol()?.psi
            }
            if (resolved != null) return resolved
        }

        // Case 2: cursor is on the name of a declaration itself → return the declaration
        PsiTreeUtil.getNonStrictParentOfType(element, KtNamedDeclaration::class.java)
    }.getOrElse { e ->
        KotlinLogger.INSTANCE.logException("KaFindUsagesComputer: resolveTargetPsi failed", e)
        null
    }

    /**
     * Searches [searchFiles] for all references to a pre-resolved [targetPsi] element.
     *
     * Unlike [compute], this method skips cursor-based resolution and operates directly on the
     * provided PSI element — useful when the caller already holds the declaration PSI (for
     * example when iterating override targets during rename).
     *
     * @param targetPsi the PSI element whose references to collect
     * @return a map from [FileObject] to the list of [OffsetRange]s referencing [targetPsi]
     */
    fun computeForTarget(targetPsi: PsiElement): Map<FileObject, List<OffsetRange>> {
        val result = mutableMapOf<FileObject, MutableList<OffsetRange>>()
        for (fo in searchFiles) {
            val kaFile = session.getKtFileForPath(fo.path) ?: continue
            val ranges = searchFileForUsages(kaFile, targetPsi)
            if (ranges.isNotEmpty()) {
                result.getOrPut(fo) { mutableListOf() }.addAll(ranges)
            }
        }
        return result
    }

    private fun searchFileForUsages(
        kaFile: org.jetbrains.kotlin.psi.KtFile,
        targetPsi: PsiElement
    ): List<OffsetRange> {
        val ranges = mutableListOf<OffsetRange>()
        runCatching {
            analyze(kaFile) {
                kaFile.accept(object : KtTreeVisitorVoid() {
                    override fun visitReferenceExpression(expression: KtReferenceExpression) {
                        super.visitReferenceExpression(expression)
                        val sym = runCatching {
                            expression.mainReference?.resolveToSymbol()
                        }.getOrNull() ?: return
                        if (sym.psi == targetPsi) {
                            ranges.add(OffsetRange(expression.textRange.startOffset, expression.textRange.endOffset))
                        }
                    }
                })
            }
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logException(
                "KaFindUsagesComputer: search failed in ${kaFile.name}", e
            )
        }
        return ranges
    }
}
