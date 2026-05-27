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
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import io.github.nbplugins.kotlin.nbm.completion.InsertableProposal
import org.netbeans.modules.csl.api.ElementHandle
import org.netbeans.modules.csl.api.ElementKind
import org.netbeans.modules.csl.api.HtmlFormatter
import org.netbeans.modules.csl.spi.DefaultCompletionProposal
import org.openide.filesystems.FileObject

/**
 * NetBeans [org.netbeans.modules.csl.api.CompletionProposal] backed by a K2 [KaCompletionItem].
 *
 * Implements [InsertableProposal] so that [KaCodeCompletionResult] can perform text
 * insertion when the user accepts this proposal.
 *
 * @param name           identifier name to display and insert
 * @param kind           NetBeans [ElementKind] for icon and categorisation
 * @param icon           icon resolved from the symbol type
 * @param anchorOffset   document offset where identifier starts
 * @param prefix         already-typed prefix (removed on insertion)
 * @param isFunctionLike whether to append `()` on insertion
 * @param signature      pre-rendered RHS signature, e.g. `"(x: Int): Boolean"` or `": String"`;
 *                       HTML-escaped when returned from [getRhsHtml] to handle generic types
 * @param sortPriority   numeric sort key; lower value = higher position in the list
 * @param fileObject     the source file where the completion was triggered; used by [getElement]
 *                       to build the [KtElementHandle] that enables hover documentation
 * @param symbolPointer  stable K2 pointer to the declaration this proposal represents; forwarded to
 *                       [KtElementHandle] so the documentation popup renders *this* candidate rather
 *                       than re-resolving the identifier under the caret
 */
class KaCompletionProposal(
    private val name: String,
    private val kind: ElementKind,
    private val icon: ImageIcon?,
    private val anchorOffset: Int,
    private val prefix: String,
    private val isFunctionLike: Boolean,
    private val signature: String,
    private val sortPriority: Int = 0,
    private val fileObject: FileObject? = null,
    private val symbolPointer: KaSymbolPointer<*>? = null,
) : DefaultCompletionProposal(), InsertableProposal {

    override fun getName(): String = name
    override fun getInsertPrefix(): String = name
    override fun getSortText(): String = name
    override fun getAnchorOffset(): Int = anchorOffset
    override fun getIcon(): ImageIcon? = icon
    override fun getKind(): ElementKind = kind

    /**
     * Returns a [KtElementHandle] pointing to the identifier start in the source file,
     * enabling [org.jetbrains.kotlin.completion.KotlinCodeCompletionHandler.documentElement]
     * to resolve the symbol and build the hover documentation popup.
     *
     * Returns `null` when [fileObject] was not provided (e.g. in tests that don't need docs).
     */
    override fun getElement(): ElementHandle? =
        fileObject?.let { KtElementHandle(it, anchorOffset, name, kind, symbolPointer) }
    override fun getLhsHtml(formatter: HtmlFormatter): String = name
    override fun getRhsHtml(formatter: HtmlFormatter): String =
        signature.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    override fun getSortPrioOverride(): Int = sortPriority

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
