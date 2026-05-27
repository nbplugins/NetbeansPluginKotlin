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

import javax.swing.text.Document
import javax.swing.text.SimpleAttributeSet
import org.netbeans.api.editor.settings.EditorStyleConstants
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.spi.editor.highlighting.HighlightsLayer
import org.netbeans.spi.editor.highlighting.HighlightsLayerFactory
import org.netbeans.spi.editor.highlighting.ZOrder
import org.netbeans.spi.editor.highlighting.support.OffsetsBag

/**
 * [HighlightsLayerFactory] that attaches [EditorStyleConstants.Tooltip] attributes to
 * Kotlin identifier ranges so that `NbToolTip` can display documentation popups when
 * the user pauses the mouse over a symbol.
 *
 * **How it works:**
 * - [KotlinSemanticAnalyzer][org.jetbrains.kotlin.highlighter.semanticanalyzer.KotlinSemanticAnalyzer]
 *   calls [applyTooltipRanges] after each K2 analysis pass, passing the same set of ranges
 *   produced by semantic highlighting.
 * - Each range gets attribute `{EditorStyleConstants.Tooltip → [KotlinTooltipResolver]}`.
 * - `NbToolTip` reads the attribute lazily via [KotlinTooltipResolver.getValue], which
 *   calls [io.github.nbplugins.kotlin.nbm.completion.KtDocumentationRenderer.buildHtml]
 *   for the identifier at the range start offset.
 *
 * **Position stability:** [OffsetsBag] uses `javax.swing.text.Position` objects internally,
 * so ranges shift with document edits and are never invalid. They become briefly stale between
 * an edit and the next semantic analysis pass (typically 300–500 ms), which is acceptable.
 *
 * Registered in `layer.xml` under `Editors/text/x-kotlin/HighlightingLayerFactories` at
 * position 1600 (above semantic color highlights at 1500).
 */
class KotlinTooltipHighlightsLayerFactory : HighlightsLayerFactory {

    companion object {
        /** Document property key for the shared tooltip [OffsetsBag]. */
        const val TOOLTIP_BAG_KEY = "io.github.nbplugins.kotlin.nbm.tooltip.bag"

        private const val LAYER_ID = "io.github.nbplugins.kotlin.nbm.tooltip"

        /** Shared [AttributeSet] carrying the lazy tooltip resolver — created once. */
        private val TOOLTIP_ATTRS: SimpleAttributeSet = SimpleAttributeSet().also { attrs ->
            attrs.addAttribute(EditorStyleConstants.Tooltip, KotlinTooltipResolver)
        }

        /**
         * Replaces the tooltip highlights for [doc] with one entry per range in [ranges].
         *
         * Called by the semantic analyzer from a background thread after each K2 analysis pass.
         * The [OffsetsBag] fires change notifications automatically so the editor picks up
         * the new highlights without an explicit repaint request.
         *
         * @param doc    the document whose tooltip bag to update
         * @param ranges identifier ranges produced by the semantic highlighting visitor
         */
        fun applyTooltipRanges(doc: Document, ranges: Collection<OffsetRange>) {
            val bag = getOrCreateBag(doc)
            val newBag = OffsetsBag(doc, true)
            for (range in ranges) {
                newBag.addHighlight(range.start, range.end, TOOLTIP_ATTRS)
            }
            bag.clear()
            bag.addAllHighlights(newBag.getHighlights(0, Int.MAX_VALUE))
        }

        private fun getOrCreateBag(doc: Document): OffsetsBag {
            val existing = doc.getProperty(TOOLTIP_BAG_KEY) as? OffsetsBag
            if (existing != null) return existing
            val bag = OffsetsBag(doc, true)
            doc.putProperty(TOOLTIP_BAG_KEY, bag)
            return bag
        }
    }

    override fun createLayers(context: HighlightsLayerFactory.Context): Array<HighlightsLayer> {
        val bag = getOrCreateBag(context.document)
        return arrayOf(
            HighlightsLayer.create(LAYER_ID, ZOrder.SYNTAX_RACK.forPosition(1600), true, bag)
        )
    }
}
