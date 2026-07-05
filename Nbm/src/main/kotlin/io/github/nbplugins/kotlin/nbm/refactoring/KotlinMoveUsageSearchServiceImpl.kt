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

import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Real (project-wide) implementation of [KotlinMoveUsageSearchService], registered as an
 * application service by [KotlinAnalysisAPISession.registerStandaloneServices].
 *
 * IDEA's move engine expects `ReferencesSearch.search()`, backed by a project index this plugin's
 * standalone container never builds (`NoOpPsiSearchHelper` makes it a no-op). This reuses the same
 * whole-project scan-and-resolve strategy already used by Find Usages (E7)'s
 * [io.github.nbplugins.kotlin.nbm.navigation.KaFindUsagesComputer]: iterate every `KtFile` the
 * session that owns [declaration] knows about and resolve each reference expression.
 */
class KotlinMoveUsageSearchServiceImpl : KotlinMoveUsageSearchService {
    override fun findUsages(declaration: KtNamedDeclaration): List<PsiReference> {
        val session = KotlinAnalysisAPISession.forProject(declaration.project) ?: return emptyList()
        val references = mutableListOf<PsiReference>()
        for (kaFile in session.fileMap.values) {
            val ktFile = session.getKtFileForPath(kaFile.path) ?: continue
            runCatching {
                analyze(ktFile) {
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                            super.visitSimpleNameExpression(expression)
                            // Skip references inside import directives: K2ReferenceMutateService's
                            // bindToElement() on an import-path segment can leave the KtImportDirective
                            // invalidated mid-replace (adding/removing import statements restructures
                            // the whole import list) standalone, without live-document sync to keep it
                            // consistent. Not needed anyway — retargeting the *code* usages (call
                            // sites, etc.) to a fully-qualified reference and then shortening already
                            // adds/removes the right import via Kotlin's own shorten-references pass.
                            if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                            // Visit simple-name expressions specifically (not the generic
                            // KtReferenceExpression, which for a call site's KtCallExpression node
                            // has no *simple-name* reference K2MoveRenameUsageInfo.Source.retarget
                            // can bindToElement() on — only its callee child, a
                            // KtNameReferenceExpression, does).
                            val reference = expression.references.filterIsInstance<KtReference>().firstOrNull() ?: return
                            val resolved = runCatching { reference.resolveToSymbol()?.psi }.getOrNull()
                            if (resolved == declaration) {
                                references += reference
                            }
                        }
                    })
                }
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinMoveUsageSearchServiceImpl: search failed in ${ktFile.name}", e
                )
            }
        }
        return references
    }
}
