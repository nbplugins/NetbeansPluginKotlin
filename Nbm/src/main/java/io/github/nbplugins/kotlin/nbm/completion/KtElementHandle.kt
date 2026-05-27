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
package io.github.nbplugins.kotlin.nbm.completion

import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.netbeans.modules.csl.api.ElementHandle
import org.netbeans.modules.csl.api.ElementKind
import org.netbeans.modules.csl.api.Modifier
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.csl.spi.ParserResult
import org.openide.filesystems.FileObject

/**
 * A lightweight [ElementHandle] that identifies a Kotlin declaration by its usage site
 * (the file and offset where the identifier appears in the source).
 *
 * Used by [KaCompletionProposal] so that [KotlinCodeCompletionHandler.documentElement] and
 * [GsfHoverProvider][org.netbeans.modules.csl.editor.completion.GsfHoverProvider] can resolve
 * the symbol at [offset] and render its rich HTML documentation via K2.
 *
 * The [offset] points to the **start of the identifier** (the anchor offset), not the caret.
 * [KtDocumentationRenderer] uses this offset to find the `KtReferenceExpression` in the K2
 * PSI tree and resolve its declaration.
 *
 * For completion proposals a [symbolPointer] to the exact selected declaration is also carried, so
 * [KtDocumentationRenderer] can render documentation for *that* candidate instead of re-resolving
 * the (identical for every list item) [offset] in the source.
 *
 * @param fileObject the source file containing the reference
 * @param offset     character offset of the identifier start (anchor offset)
 * @param name       the identifier text, used for display and [getOffsetRange]
 * @param elementKind the CSL element kind (e.g. [ElementKind.METHOD], [ElementKind.CLASS])
 * @param symbolPointer stable K2 pointer to the represented declaration, or `null` for usage-site
 *                   (hover) handles that should resolve purely from [offset]
 */
class KtElementHandle(
    private val fileObject: FileObject,
    val offset: Int,
    private val name: String,
    private val elementKind: ElementKind,
    val symbolPointer: KaSymbolPointer<*>? = null,
) : ElementHandle {

    /** Returns the file containing the reference. */
    override fun getFileObject(): FileObject = fileObject

    /** Always `"text/x-kotlin"`. */
    override fun getMimeType(): String = "text/x-kotlin"

    /** The identifier text at the usage site. */
    override fun getName(): String = name

    /** Not applicable for a usage-site handle; returns an empty string. */
    override fun getIn(): String = ""

    /** The CSL element kind forwarded from the completion item. */
    override fun getKind(): ElementKind = elementKind

    /** No modifier information is carried; returns an empty set. */
    override fun getModifiers(): Set<Modifier> = emptySet()

    /**
     * Two handles are equal when they point to the same offset in the same file.
     * Name and kind are intentionally excluded — the same position always identifies
     * the same symbol.
     */
    override fun signatureEquals(handle: ElementHandle): Boolean =
        handle is KtElementHandle &&
            handle.offset == offset &&
            handle.fileObject == fileObject

    /**
     * Returns an [OffsetRange] spanning `[offset, offset + name.length)`.
     * The [result] parameter is unused; the range is computed purely from stored fields.
     */
    override fun getOffsetRange(result: ParserResult?): OffsetRange =
        OffsetRange(offset, offset + name.length)
}
