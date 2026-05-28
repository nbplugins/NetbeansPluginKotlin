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
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.netbeans.modules.csl.api.ElementKind

/**
 * Pre-rendered completion data for a single Kotlin declaration symbol.
 *
 * All fields are computed inside an `analyze {}` block by [KaCompletionProvider.getItemsAt]
 * so that K2 symbol lifetimes are respected — symbols must not be accessed outside the block.
 *
 * @param name         identifier name to insert and display
 * @param kind         NetBeans [ElementKind] for categorisation
 * @param isFunctionLike whether to append `()` on insertion
 * @param signature    pre-rendered RHS signature string, e.g. `"(x: Int): Boolean"` or `": String"`;
 *                     empty string for symbols without meaningful signature info (e.g. classes)
 * @param icon         icon resolved from the symbol type and properties
 * @param sortPriority numeric sort key: lower = higher in the list; computed from [KaScopeKind]
 *                     priority and symbol kind (properties before methods within the same scope)
 * @param symbolPointer stable K2 pointer to the exact declaration this item represents, created
 *                     inside the `analyze {}` block. Restored in a later session by
 *                     [io.github.nbplugins.kotlin.nbm.completion.KtDocumentationRenderer] to render
 *                     documentation for *this* candidate (not the identifier under the caret), so
 *                     the doc popup follows the selected list item.
 */
data class KaCompletionItem(
    val name: String,
    val kind: ElementKind,
    val isFunctionLike: Boolean,
    val signature: String,
    val icon: ImageIcon?,
    val sortPriority: Int = 0,
    val symbolPointer: KaSymbolPointer<*>? = null,
)
