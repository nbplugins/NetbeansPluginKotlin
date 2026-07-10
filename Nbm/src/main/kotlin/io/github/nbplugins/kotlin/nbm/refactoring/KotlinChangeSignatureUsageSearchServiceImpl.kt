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

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.KotlinChangeSignatureUsageSearchService
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import com.intellij.psi.util.PsiTreeUtil

/**
 * Real (project-wide) implementation of [KotlinChangeSignatureUsageSearchService], registered as an
 * application service by [KotlinAnalysisAPISession.registerStandaloneServices].
 *
 * IDEA's ported Change Signature engine expects `ReferencesSearch`/`MethodReferencesSearch`, backed
 * by a project index this plugin's standalone container never builds. This reuses the same
 * whole-project scan-and-resolve strategy already used by Find Usages (E7)'s
 * [io.github.nbplugins.kotlin.nbm.navigation.KaFindUsagesComputer] and Move Declaration (E9.7)'s
 * [KotlinMoveUsageSearchServiceImpl]: iterate every `KtFile` the session that owns [element] knows
 * about and resolve each simple-name reference.
 *
 * M1 scope: only [KtSimpleNameExpression] references are visited, covering plain function/
 * constructor calls and parameter-name references inside the body. Overrides, callable references
 * (`::foo`), constructor delegation, destructuring, enum entries, and by-convention/operator calls
 * (M2/M3) resolve through different PSI node kinds and are not yet visited here.
 */
class KotlinChangeSignatureUsageSearchServiceImpl : KotlinChangeSignatureUsageSearchService {
    override fun findUsages(element: PsiElement): List<PsiReference> {
        val session = KotlinAnalysisAPISession.forProject(element.project) ?: return emptyList()
        val references = mutableListOf<PsiReference>()
        for (kaFile in session.fileMap.values) {
            val ktFile = session.getKtFileForPath(kaFile.path) ?: continue
            runCatching {
                analyze(ktFile) {
                    ktFile.accept(object : KtTreeVisitorVoid() {
                        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                            super.visitSimpleNameExpression(expression)
                            // Same import-directive exclusion as KotlinMoveUsageSearchServiceImpl:
                            // no live-document sync to keep the import list consistent standalone.
                            if (PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) != null) return
                            val reference = expression.references.filterIsInstance<KtReference>().firstOrNull() ?: return
                            val resolved = runCatching { reference.resolveToSymbol()?.psi }.getOrNull()
                            if (resolved == element) {
                                references += reference
                            }
                        }
                    })
                }
            }.onFailure { e ->
                KotlinLogger.INSTANCE.logException(
                    "KotlinChangeSignatureUsageSearchServiceImpl: search failed in ${ktFile.name}", e
                )
            }
        }
        return references
    }
}
