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
package io.github.nbplugins.kotlin.nbm.structurescanner

import javax.swing.ImageIcon
import org.jetbrains.kotlin.psi.KtProperty
import org.netbeans.modules.csl.api.ElementKind
import org.netbeans.modules.csl.api.HtmlFormatter
import org.netbeans.modules.csl.api.Modifier
import org.netbeans.modules.csl.api.StructureItem
import org.openide.util.ImageUtilities

/**
 * K2 structure item for a Kotlin property declaration.
 *
 * The [displayName] (property name and inferred or declared type) is computed eagerly inside an
 * [org.jetbrains.kotlin.analysis.api.analyze] block by [KaStructureScanner], so `getName()` is
 * safe to call without holding a [org.jetbrains.kotlin.resolve.BindingContext].
 *
 * Properties are always leaf nodes in the structure tree (no children).
 *
 * @param property the PSI node for the property declaration
 * @param displayName pre-computed display string, e.g. `"name: Type"`
 */
class KaPropertyStructureItem(
    private val property: KtProperty,
    private val displayName: String
) : StructureItem {

    override fun getName() = displayName
    override fun getSortText() = property.name
    override fun getHtml(formatter: HtmlFormatter) = displayName
    override fun getElementHandle() = null
    override fun getKind() = ElementKind.PROPERTY
    override fun getModifiers() = emptySet<Modifier>()
    override fun isLeaf() = true
    override fun getNestedItems() = emptyList<StructureItem>()
    override fun getPosition() = property.textRange.startOffset.toLong()
    override fun getEndPosition() = property.textRange.endOffset.toLong()
    override fun getCustomIcon() =
        ImageIcon(ImageUtilities.loadImage("org/jetbrains/kotlin/completionIcons/field.png"))
}
