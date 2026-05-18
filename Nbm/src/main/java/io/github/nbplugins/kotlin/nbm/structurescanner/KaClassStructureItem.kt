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
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.netbeans.modules.csl.api.ElementKind
import org.netbeans.modules.csl.api.HtmlFormatter
import org.netbeans.modules.csl.api.Modifier
import org.netbeans.modules.csl.api.StructureItem
import org.openide.util.ImageUtilities

/**
 * K2 structure item for a Kotlin class, object, or interface declaration.
 *
 * The [displayName] (class name and supertype list) and [nestedItems] (inner classes, methods,
 * properties) are computed eagerly by [KaStructureScanner] before construction so that no
 * [org.jetbrains.kotlin.resolve.BindingContext] is retained.
 *
 * @param psiElement the PSI node for the class or object declaration
 * @param displayName pre-computed display string, e.g. `"MyClass::Base1, Base2"`
 * @param nestedItems pre-computed list of inner structure items
 */
class KaClassStructureItem(
    private val psiElement: KtClassOrObject,
    private val displayName: String,
    private val nestedItems: List<StructureItem>
) : StructureItem {

    override fun getName() = displayName
    override fun getSortText() = psiElement.name
    override fun getHtml(formatter: HtmlFormatter) = displayName
    override fun getElementHandle() = null
    override fun getKind() = ElementKind.CLASS
    override fun getModifiers() = emptySet<Modifier>()
    /** A class is a leaf only when it has no displayable members. */
    override fun isLeaf() = nestedItems.isEmpty()
    override fun getNestedItems() = nestedItems
    override fun getPosition() = psiElement.textRange.startOffset.toLong()
    override fun getEndPosition() = psiElement.textRange.endOffset.toLong()
    override fun getCustomIcon() =
        ImageIcon(ImageUtilities.loadImage("org/jetbrains/kotlin/completionIcons/class.png"))
}
