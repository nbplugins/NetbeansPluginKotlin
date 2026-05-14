/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.completion

import javax.swing.text.Document
import org.jetbrains.kotlin.completion.InsertableProposal
import org.netbeans.modules.csl.api.CodeCompletionResult
import org.netbeans.modules.csl.api.CompletionProposal

/**
 * [CodeCompletionResult] backed by a pre-built list of K2 [CompletionProposal]s.
 *
 * Used by the K2 primary path in [org.jetbrains.kotlin.completion.KotlinCodeCompletionHandler]
 * when [org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult.kaKtFile] is
 * non-null and [KaCompletionProvider] returns at least one proposal.
 *
 * @param doc       the document being edited (used for text insertion)
 * @param proposals pre-built list of completion proposals
 */
class KaCodeCompletionResult(
    private val doc: Document,
    private val proposals: List<CompletionProposal>,
) : CodeCompletionResult() {

    /** Returns all completion proposals produced by the K2 path. */
    override fun getItems(): List<CompletionProposal> = proposals

    override fun isTruncated(): Boolean = false
    override fun isFilterable(): Boolean = false

    /**
     * Inserts [item] into the document by delegating to [InsertableProposal.doInsert].
     *
     * @param item the proposal selected by the user
     * @return `true` always (insertion handled)
     */
    override fun insert(item: CompletionProposal): Boolean {
        (item as? InsertableProposal)?.doInsert(doc)
        return true
    }
}
