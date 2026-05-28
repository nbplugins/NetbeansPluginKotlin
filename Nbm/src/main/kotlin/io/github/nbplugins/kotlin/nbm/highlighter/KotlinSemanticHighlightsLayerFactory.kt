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
package io.github.nbplugins.kotlin.nbm.highlighter

import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AttributeSet
import javax.swing.text.Document
import javax.swing.text.SimpleAttributeSet
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.spi.editor.highlighting.HighlightsLayer
import org.netbeans.spi.editor.highlighting.HighlightsLayerFactory
import org.netbeans.spi.editor.highlighting.ZOrder
import org.netbeans.spi.editor.highlighting.support.OffsetsBag
import org.openide.util.lookup.ServiceProvider

/**
 * [HighlightsLayerFactory] that renders Kotlin semantic highlights using named
 * `FontAndColors.xml` color categories resolved at runtime from [FontColorSettings].
 *
 * **Threading model**: [createLayers] is called on the EDT when the editor opens; it
 * retrieves (or creates) a per-document [OffsetsBag] stored under [HIGHLIGHTS_BAG_KEY].
 * [applyHighlights] is called from the CSL background thread by [KotlinSemanticAnalyzer]
 * and updates the same bag. [OffsetsBag] fires change notifications automatically, causing
 * NetBeans to repaint the affected regions on the EDT.
 *
 * **Color lookup**: each color category name (e.g. `"KOTLIN_MUTABLE_VARIABLE"`) is resolved
 * via [FontColorSettings.getTokenFontColors] from the `text/x-kotlin` MIME lookup. Names
 * correspond to `<fontcolor name="...">` entries in `FontAndColors.xml` (light) or
 * `FontAndColorsDark.xml` (dark). When multiple names apply to the same range (e.g.
 * `KOTLIN_INSTANCE_PROPERTY` + `KOTLIN_MUTABLE_VARIABLE`), their [AttributeSet]s are
 * composed via [composeAttributes] so both contributions are visible simultaneously.
 *
 * Registered as a [HighlightsLayerFactory] for `text/x-kotlin` at position 1500
 * (above CSL syntax coloring at 1000, below error highlighting at 2000).
 */
@ServiceProvider(service = HighlightsLayerFactory::class, path = "Editors/text/x-kotlin")
class KotlinSemanticHighlightsLayerFactory : HighlightsLayerFactory {

    companion object {
        /** Document property key for the shared [OffsetsBag]. */
        const val HIGHLIGHTS_BAG_KEY = "io.github.nbplugins.kotlin.nbm.highlights.bag"

        private const val LAYER_ID = "io.github.nbplugins.kotlin.nbm.highlighter.semantic"

        /** Content signature of the Fonts & Colors preview file (`preview.kt`). */
        private const val PREVIEW_SIGNATURE = "/* Block comment */\npackage hello"

        /**
         * Computes highlight [AttributeSet]s from [highlights] and applies them to the
         * per-document [OffsetsBag], replacing any previous semantic highlights.
         *
         * Called by [KotlinSemanticAnalyzer] from a background thread after each parse.
         *
         * @param doc the document to update; the bag is retrieved or created on demand
         * @param highlights map from source range to ordered list of color category names
         */
        fun applyHighlights(doc: Document, highlights: Map<OffsetRange, List<String>>) {
            val bag = getOrCreateBag(doc)

            val newBag = OffsetsBag(doc, true)
            for ((range, names) in highlights) {
                var merged: AttributeSet = SimpleAttributeSet.EMPTY
                for (name in names) {
                    val attrs = KotlinColorResolver.attributeSetFor(name) ?: continue
                    merged = composeAttributes(merged, attrs)
                }
                if (merged !== SimpleAttributeSet.EMPTY) {
                    newBag.addHighlight(range.start, range.end, merged)
                }
            }
            bag.clear()
            bag.addAllHighlights(newBag.getHighlights(0, Int.MAX_VALUE))
        }

        /**
         * Retrieves the per-document [OffsetsBag] from [doc]'s property map, creating and
         * storing one if it does not yet exist.
         */
        private fun getOrCreateBag(doc: Document): OffsetsBag {
            val existing = doc.getProperty(HIGHLIGHTS_BAG_KEY) as? OffsetsBag
            if (existing != null) return existing
            val bag = OffsetsBag(doc, true)
            doc.putProperty(HIGHLIGHTS_BAG_KEY, bag)
            return bag
        }

        /**
         * Composes two [AttributeSet]s so that attributes from [overlay] take precedence
         * over [base] for properties defined in both.
         */
        private fun composeAttributes(base: AttributeSet, overlay: AttributeSet): AttributeSet {
            if (base === SimpleAttributeSet.EMPTY) return overlay
            val result = SimpleAttributeSet(base)
            result.addAttributes(overlay)
            return result
        }
    }

    override fun createLayers(context: HighlightsLayerFactory.Context): Array<HighlightsLayer> {
        val doc = context.document
        val bag = getOrCreateBag(doc)
        KotlinLogger.INSTANCE.logInfo(
            "KotlinSemanticHighlightsLayerFactory.createLayers: doc=${doc.javaClass.simpleName}, length=${doc.length}"
        )
        // The Fonts & Colors settings preview pane is a text/x-kotlin editor but is not parsed
        // by CSL (no project, no SemanticAnalyzer run), so its bag would stay empty. Populate it
        // here from the pre-computed snapshot instead.
        if (isPreviewDocument(doc)) {
            KotlinLogger.INSTANCE.logInfo("KotlinSemanticHighlightsLayerFactory: detected preview document")
            schedulePreviewHighlights(doc)
        }
        return arrayOf(
            HighlightsLayer.create(
                LAYER_ID,
                ZOrder.SYNTAX_RACK.forPosition(1500),
                true,
                bag
            )
        )
    }

    /**
     * `true` when [doc] is the Options dialog Fonts & Colors preview document.
     *
     * The preview document has no [FileObject] (no `StreamDescriptionProperty`), so we detect it
     * by content: if the first line matches the known preview file signature, it is the preview.
     */
    private fun isPreviewDocument(doc: Document): Boolean {
        val fo = ProjectUtils.getFileObjectForDocument(doc)
        if (fo != null) {
            val isPreview = fo.path.contains("PreviewExamples")
            KotlinLogger.INSTANCE.logInfo("isPreviewDocument: path=${fo.path}, isPreview=$isPreview")
            return isPreview
        }
        // Preview documents have no FileObject — detect by content signature.
        val isPreview = doc.length > 0 &&
            runCatching {
                doc.getText(0, doc.length.coerceAtMost(PREVIEW_SIGNATURE.length)) == PREVIEW_SIGNATURE
            }.getOrDefault(false)
        KotlinLogger.INSTANCE.logInfo("isPreviewDocument: FileObject is null, signatureMatch=$isPreview")
        return isPreview
    }

    /**
     * Applies the pre-computed preview highlights to [doc], deferring until the document has
     * content if it is still empty when the layer is created (NetBeans may create layers before
     * loading the preview text).
     */
    private fun schedulePreviewHighlights(doc: Document) {
        val highlights = PreviewSemanticHighlightsLoader.load()
        KotlinLogger.INSTANCE.logInfo(
            "schedulePreviewHighlights: loaded ${highlights.size} ranges, doc.length=${doc.length}"
        )
        if (doc.length > 0) {
            applyHighlights(doc, highlights)
            return
        }
        doc.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                doc.removeDocumentListener(this)
                KotlinLogger.INSTANCE.logInfo(
                    "schedulePreviewHighlights: document inserted, length=${doc.length}, applying highlights"
                )
                applyHighlights(doc, highlights)
            }
            override fun removeUpdate(e: DocumentEvent) {}
            override fun changedUpdate(e: DocumentEvent) {}
        })
    }
}
