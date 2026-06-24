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
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.concurrency.AsyncFuture
import com.intellij.concurrency.AsyncFutureFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiNonJavaFileReferenceProcessor
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.PsiSearchHelper.SearchCostResult
import com.intellij.psi.search.SearchRequestCollector
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Processor

/**
 * No-op [PsiSearchHelper] implementation for the K2 standalone analysis container.
 *
 * The full IntelliJ implementation ([com.intellij.psi.impl.search.PsiSearchHelperImpl]) requires
 * file-based indexes, [com.intellij.openapi.project.DumbService], and a managed `PsiManagerEx`
 * — infrastructure that the standalone Analysis API session does not provide. Refactorings such
 * as Inline Variable funnel every reference search through [PsiSearchHelper.getInstance];
 * without a service the call throws `IllegalState: @NotNull method ... must not return null`.
 *
 * This stub returns "found nothing, processed cleanly" for every search call. That is correct
 * for the use cases we currently support:
 *  - Inline Variable on a `val` with an initializer in declaration does not actually need
 *    cross-file write-usage discovery — the IDEA path takes the `initializerInDeclaration != null`
 *    branch as soon as `writeUsages` is empty.
 *  - Other refactorings that depend on true reference enumeration drive their search through
 *    [io.github.nbplugins.kotlin.nbm.resolve.KaResolutionUtils] (KaSession-based), not via this stub.
 *
 * Registered as a project service in [KotlinAnalysisAPISession.installNoOpPsiSearchHelper].
 */
class NoOpPsiSearchHelper : PsiSearchHelper {

    override fun findCommentsContainingIdentifier(identifier: String, searchScope: SearchScope): Array<PsiElement> = emptyArray()

    override fun processCommentsContainingIdentifier(
        identifier: String, searchScope: SearchScope, processor: Processor<in PsiElement>,
    ): Boolean = true

    override fun processCandidateFilesForText(
        scope: GlobalSearchScope, searchContext: Short, caseSensitively: Boolean,
        text: String, processor: Processor<in VirtualFile>,
    ): Boolean = true

    override fun findFilesWithPlainTextWords(word: String): Array<PsiFile> = emptyArray()

    override fun processUsagesInNonJavaFiles(
        qName: String, processor: PsiNonJavaFileReferenceProcessor, searchScope: GlobalSearchScope,
    ): Boolean = true

    override fun processUsagesInNonJavaFiles(
        originalElement: PsiElement?, qName: String,
        processor: PsiNonJavaFileReferenceProcessor, searchScope: GlobalSearchScope,
    ): Boolean = true

    /** The "use scope" of an element in the stub is just the element's own file (local scope). */
    override fun getUseScope(element: PsiElement): SearchScope =
        element.containingFile?.let { LocalSearchScope(it) } ?: LocalSearchScope(element)

    override fun processAllFilesWithWord(
        word: String, scope: GlobalSearchScope,
        processor: Processor<in PsiFile>, caseSensitively: Boolean,
    ): Boolean = true

    override fun processAllFilesWithWordInText(
        word: String, scope: GlobalSearchScope,
        processor: Processor<in PsiFile>, caseSensitively: Boolean,
    ): Boolean = true

    override fun processAllFilesWithWordInComments(
        word: String, scope: GlobalSearchScope, processor: Processor<in PsiFile>,
    ): Boolean = true

    override fun processAllFilesWithWordInLiterals(
        word: String, scope: GlobalSearchScope, processor: Processor<in PsiFile>,
    ): Boolean = true

    override fun processRequests(
        request: SearchRequestCollector, processor: Processor<in PsiReference>,
    ): Boolean = true

    override fun processRequestsAsync(
        request: SearchRequestCollector, processor: Processor<in PsiReference>,
    ): AsyncFuture<Boolean> = AsyncFutureFactory.wrap(true)

    override fun processElementsWithWord(
        processor: TextOccurenceProcessor, searchScope: SearchScope,
        text: String, searchContext: Short, caseSensitive: Boolean,
    ): Boolean = true

    override fun processElementsWithWord(
        processor: TextOccurenceProcessor, searchScope: SearchScope,
        text: String, searchContext: Short, caseSensitive: Boolean, processInjectedPsi: Boolean,
    ): Boolean = true

    override fun processElementsWithWordAsync(
        processor: TextOccurenceProcessor, searchScope: SearchScope,
        text: String, searchContext: Short, caseSensitive: Boolean,
    ): AsyncFuture<Boolean> = AsyncFutureFactory.wrap(true)

    override fun isCheapEnoughToSearch(
        name: String, scope: GlobalSearchScope,
        fileToIgnoreOccurrencesIn: PsiFile?, progress: ProgressIndicator?,
    ): SearchCostResult = SearchCostResult.ZERO_OCCURRENCES
}
