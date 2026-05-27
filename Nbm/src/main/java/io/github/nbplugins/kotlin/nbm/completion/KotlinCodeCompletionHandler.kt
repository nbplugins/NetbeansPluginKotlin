/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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

import io.github.nbplugins.kotlin.nbm.completion.KaCodeCompletionResult
import io.github.nbplugins.kotlin.nbm.completion.KaCompletionProposalFactory
import io.github.nbplugins.kotlin.nbm.completion.KaCompletionProvider
import io.github.nbplugins.kotlin.nbm.completion.KtDocumentationRenderer
import io.github.nbplugins.kotlin.nbm.completion.KtElementHandle
import java.util.concurrent.Callable
import org.jetbrains.kotlin.log.KotlinLogger
import javax.swing.text.Document
import javax.swing.text.JTextComponent
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.CodeCompletionContext
import org.netbeans.modules.csl.api.CodeCompletionHandler2
import org.netbeans.modules.csl.api.CodeCompletionResult
import org.netbeans.modules.csl.api.CompletionProposal
import org.netbeans.modules.csl.api.Documentation
import org.netbeans.modules.csl.api.ElementHandle
import org.netbeans.modules.csl.api.ParameterInfo
import org.netbeans.modules.csl.spi.ParserResult
import org.netbeans.modules.csl.api.CodeCompletionHandler.QueryType

class KotlinCodeCompletionHandler : CodeCompletionHandler2 {

    /**
     * Builds the documentation popup for [element].
     *
     * When [element] is a [KtElementHandle] (produced by [KaCompletionProposal.getElement]),
     * delegates to [KtDocumentationRenderer] to resolve the K2 symbol and render a rich HTML
     * popup with a syntax-highlighted signature, container info, and KDoc sections.
     *
     * Falls back to a URL-based popup for [ElementHandle.UrlHandle] and returns an empty
     * [Documentation] for all other handle types.
     *
     * @param info   the current parser result (used to locate the project)
     * @param element the element handle from the accepted or hovered completion proposal
     * @param cancel  callable that returns `true` when the request was cancelled
     * @return a [Documentation] instance for the popup; never `null`
     */
    override fun documentElement(info: ParserResult, element: ElementHandle,
                                 cancel: Callable<Boolean>): Documentation {
        if (element is KtElementHandle) {
            val infoFile = info.snapshot.source.fileObject
            val project = ProjectUtils.getKotlinProjectForFileObject(infoFile)
                ?: ProjectUtils.getValidProject()
            if (project != null) {
                val pointer = element.symbolPointer
                val html = if (pointer != null) {
                    KtDocumentationRenderer.buildHtmlForPointer(project, element.fileObject, pointer)
                } else {
                    KtDocumentationRenderer.buildHtml(project, element.fileObject, element.offset)
                }
                if (!html.isNullOrEmpty()) return Documentation.create(html)
            }
        }
        if (element is ElementHandle.UrlHandle) return Documentation.create(element.url)
        return Documentation.create("")
    }

    override fun document(info: ParserResult, element: ElementHandle) = ""

    override fun resolveLink(link: String, handle: ElementHandle) = null

    override fun getPrefix(info: ParserResult, caretOffset: Int, upToOffset: Boolean) = null

    override fun resolveTemplateVariable(variable: String, info: ParserResult, caretOffset: Int,
                                         name: String, parameters: Map<*, *>) = null

    override fun getApplicableTemplates(doc: Document, selectionBegin: Int, selectionEnd: Int) = emptySet<String>()

    override fun parameters(info: ParserResult, caretOffset: Int, proposal: CompletionProposal?): ParameterInfo = ParameterInfo.NONE

    override fun getAutoQuery(component: JTextComponent, typedText: String): QueryType {
        if (typedText.isNotEmpty()) {
            if (typedText.endsWith(".")) return QueryType.COMPLETION
        }
        return QueryType.NONE
    }

    override fun complete(context: CodeCompletionContext): CodeCompletionResult? {
        val parserResult = context.parserResult as? KotlinParserResult ?: return null
        val file = parserResult.snapshot.source.fileObject
        val doc = ProjectUtils.getDocumentFromFileObject(file)
        val caretOffset = context.caretOffset
        val prefix = context.prefix ?: ""

        // K2 primary path: use Analysis API when a K2-session KtFile is available
        val kaKtFile = parserResult.kaKtFile
        if (kaKtFile == null) {
            KotlinLogger.INSTANCE.logWarning("complete(): kaKtFile is null -> returning null (no proposals)")
            return null
        }
        val identOffset = (caretOffset - prefix.length).coerceAtLeast(0)
        val isAfterDot = identOffset > 0 && doc?.getText(identOffset - 1, 1) == "."
        val k2Proposals = KaCompletionProvider.getItemsAt(kaKtFile, caretOffset, prefix, isAfterDot)
            .map { KaCompletionProposalFactory.toProposal(it, identOffset, prefix, file) }
        if (k2Proposals.isNotEmpty()) {
            return KaCodeCompletionResult(doc, k2Proposals)
        }
        KotlinLogger.INSTANCE.logWarning("complete(): no proposals -> returning null")
        return null
    }

}