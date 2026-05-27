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
 *******************************************************************************/
package io.github.nbplugins.kotlin.nbm.hover

import io.github.nbplugins.kotlin.nbm.completion.KtDocumentationRenderer
import java.util.concurrent.CompletableFuture
import javax.swing.text.Document
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.spi.lsp.HoverProvider

/**
 * In-editor hover tooltip provider for Kotlin files.
 *
 * Registered under `Editors/text/x-kotlin/HoverProviders` in `layer.xml` so that
 * NetBeans calls [getHoverContent] when the user pauses the mouse over a Kotlin symbol.
 * Delegates directly to [KtDocumentationRenderer.buildHtml] to produce a rich HTML
 * popup with a syntax-highlighted signature, container information, and KDoc sections.
 *
 * Unlike [org.netbeans.modules.csl.editor.completion.GsfHoverProvider], this provider
 * resolves the symbol at the exact hover offset rather than running a completion query
 * and taking the first result, which ensures correct documentation for every symbol.
 *
 * @see KtDocumentationRenderer
 */
class KotlinHoverProvider : HoverProvider {

    /**
     * Returns an HTML documentation string for the Kotlin symbol at [offset] in [doc],
     * completing with `null` when no documentation is available (no project, no symbol,
     * or whitespace/keyword at the offset).
     *
     * Analysis runs synchronously; callers should invoke from a background thread.
     *
     * @param doc    the editor document containing the Kotlin source
     * @param offset the caret/mouse offset whose symbol to document
     * @return a future completing with the HTML string, or `null` if unavailable
     */
    override fun getHoverContent(doc: Document, offset: Int): CompletableFuture<String?> {
        val fo = ProjectUtils.getFileObjectForDocument(doc)
            ?: return CompletableFuture.completedFuture(null)
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return CompletableFuture.completedFuture(null)
        val html = KtDocumentationRenderer.buildHtml(project, fo, offset)
        return CompletableFuture.completedFuture(html)
    }
}
