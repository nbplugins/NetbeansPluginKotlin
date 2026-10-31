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
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import org.jetbrains.kotlin.psi.KtFile

/**
 * Answers [ReferencesSearch] for Kotlin PSI inside a [LocalSearchScope], without any index.
 *
 * The IDE feeds `ReferencesSearch` from the `com.intellij.referencesSearch` extension point, whose
 * Kotlin contributors search the reference index. A standalone K2 session loads no plugin
 * descriptors and has no search index (`NoOpPsiSearchHelper`), so the point stays empty and every
 * search returns nothing — for example the usages of a property inside its getter and setter,
 * which `K2ConvertGettersAndSettersToPropertyProcessing` needs in order to rewrite them to
 * `field` and to drop the accessors that become redundant.
 *
 * This executor walks the PSI of the scope instead: for every candidate element it takes the
 * references the language implementation created and keeps those whose
 * [PsiReference.isReferenceTo] resolves to the searched declaration. That is index-free, so it
 * works in a standalone session, and it is exact — the match is a real resolution, not a text
 * comparison.
 *
 * Only [LocalSearchScope]s are answered. Global scopes stay empty: serving them would require
 * searching every source file of the session for every query, and the Kotlin refactorings that
 * pass a global scope use their own NetBeans-backed usage services (see
 * [io.github.nbplugins.kotlin.nbm.refactoring.KotlinChangeSignatureUsageSearchServiceImpl]).
 * Only Kotlin files are walked, because the Java references of this environment are not resolved
 * against the K2 session.
 *
 * Registered on the application extension area in
 * [KotlinAnalysisAPISession.installReferencesSearch].
 */
class KotlinReferencesSearchExecutor :
    QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {

    /**
     * Reports the references to [queryParameters]'s element found inside its local search scope.
     *
     * @param queryParameters the searched element and the scope restricting the search
     * @param consumer receives every reference that resolves to the searched element
     * @return `true` when the scope was searched completely, `false` when [consumer] stopped the
     * search or the query is no longer valid
     */
    override fun execute(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ): Boolean {
        val target = queryParameters.elementToSearch
        val scope = queryParameters.effectiveSearchScope as? LocalSearchScope ?: return true
        for (root in scope.scope) {
            if (!queryParameters.isQueryValid) return false
            if (!root.isValid) continue
            if (!processRoot(root, target, consumer)) return false
        }
        return true
    }

    /**
     * Reports the references to [target] inside [root].
     *
     * [root] is either a whole file (the usual case) or a single element the caller narrowed the
     * scope to; both are walked the same way, and non-Kotlin roots are skipped.
     *
     * @param root the element whose subtree is searched
     * @param target the declaration whose references are collected
     * @param consumer receives every matching reference
     * @return `true` when the subtree was searched completely, `false` when [consumer] stopped
     */
    private fun processRoot(
        root: PsiElement,
        target: PsiElement,
        consumer: Processor<in PsiReference>,
    ): Boolean {
        val rootFile = root as? KtFile ?: root.containingFile as? KtFile ?: return true
        if (!rootFile.isValid) return true
        var processed = true
        PsiTreeUtil.processElements(root) { candidate ->
            for (reference in candidate.references) {
                // A reference whose resolution throws (an unresolved or a half-replaced reference,
                // as the J2K post-processing produces while it rewrites the file) is simply not a
                // usage of this declaration.
                val matches = runCatching { reference.isReferenceTo(target) }.getOrDefault(false)
                if (!matches) continue
                if (!consumer.process(reference)) {
                    processed = false
                    return@processElements false
                }
            }
            true
        }
        return processed
    }
}
