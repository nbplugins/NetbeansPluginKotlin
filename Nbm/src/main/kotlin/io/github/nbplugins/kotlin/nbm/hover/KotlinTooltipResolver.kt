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
import javax.swing.text.Document
import javax.swing.text.JTextComponent
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.spi.editor.highlighting.HighlightAttributeValue

/**
 * Lazy tooltip resolver for Kotlin symbols.
 *
 * Stored as the value of [org.netbeans.api.editor.settings.EditorStyleConstants.Tooltip]
 * in the tooltip [org.netbeans.spi.editor.highlighting.HighlightsLayer] managed by
 * [KotlinTooltipHighlightsLayerFactory]. When `NbToolTip` reads the tooltip at a hover
 * position it calls [getValue], which delegates to [KtDocumentationRenderer.buildHtml]
 * to produce an HTML string with the symbol signature and KDoc sections.
 *
 * This is a singleton — the same instance is reused for every tooltip highlight range.
 */
object KotlinTooltipResolver : HighlightAttributeValue<String> {

    /**
     * Returns the HTML documentation for the Kotlin symbol whose declaration starts at
     * [startOffset] in [doc], or `null` if no documentation is available.
     *
     * Called by the NetBeans editor infrastructure from a background thread when the user
     * pauses the mouse over a Kotlin identifier.
     *
     * @param textComponent the editor component (unused; position comes from [startOffset])
     * @param doc           the editor document
     * @param attributeKey  [org.netbeans.api.editor.settings.EditorStyleConstants.Tooltip]
     * @param startOffset   start of the highlight range (i.e., the identifier start offset)
     * @param endOffset     end of the highlight range
     * @return HTML documentation string, or `null` if none
     */
    override fun getValue(
        textComponent: JTextComponent,
        doc: Document,
        attributeKey: Any,
        startOffset: Int,
        endOffset: Int,
    ): String? {
        val fo = ProjectUtils.getFileObjectForDocument(doc) ?: return null
        val project = ProjectUtils.getKotlinProjectForFileObject(fo)
            ?: ProjectUtils.getValidProject()
            ?: return null
        return KtDocumentationRenderer.buildHtml(project, fo, startOffset)
    }
}
