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

import javax.swing.ImageIcon
import javax.swing.text.Document
import org.jetbrains.kotlin.completion.InsertableProposal
import org.netbeans.modules.csl.api.ElementKind
import org.netbeans.modules.csl.api.HtmlFormatter
import org.netbeans.modules.csl.spi.DefaultCompletionProposal

/**
 * NetBeans [org.netbeans.modules.csl.api.CompletionProposal] backed by a K2 [KaCompletionProposalFactory] symbol.
 *
 * Implements [InsertableProposal] so that [KaCodeCompletionResult] can perform text
 * insertion when the user accepts this proposal.
 *
 * @param name       identifier name to display and insert
 * @param kind       NetBeans [ElementKind] for icon and categorisation
 * @param icon       icon resolved from the symbol type
 * @param anchorOffset document offset where identifier starts
 * @param prefix     already-typed prefix (removed on insertion)
 * @param isFunctionLike whether to append `()` on insertion
 */
class KaCompletionProposal(
    private val name: String,
    private val kind: ElementKind,
    private val icon: ImageIcon?,
    private val anchorOffset: Int,
    private val prefix: String,
    private val isFunctionLike: Boolean,
) : DefaultCompletionProposal(), InsertableProposal {

    override fun getName(): String = name
    override fun getInsertPrefix(): String = name
    override fun getSortText(): String = name
    override fun getAnchorOffset(): Int = anchorOffset
    override fun getIcon(): ImageIcon? = icon
    override fun getKind(): ElementKind = kind
    override fun getElement() = null
    override fun getLhsHtml(formatter: HtmlFormatter): String = name
    override fun getRhsHtml(formatter: HtmlFormatter): String = ""
    override fun getSortPrioOverride(): Int = when (kind) {
        ElementKind.FIELD -> 20
        ElementKind.METHOD -> 30
        ElementKind.CLASS -> 40
        else -> 150
    }

    /**
     * Inserts the name at [anchorOffset], replacing [prefix]. Appends `()` for function-like symbols.
     *
     * @param document the document to modify
     */
    override fun doInsert(document: Document) {
        document.remove(anchorOffset, prefix.length)
        val insertion = if (isFunctionLike) "$name()" else name
        document.insertString(anchorOffset, insertion, null)
    }
}
