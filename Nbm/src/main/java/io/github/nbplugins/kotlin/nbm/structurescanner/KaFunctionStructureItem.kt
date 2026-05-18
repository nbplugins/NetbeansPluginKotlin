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
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.netbeans.modules.csl.api.ElementKind
import org.netbeans.modules.csl.api.HtmlFormatter
import org.netbeans.modules.csl.api.Modifier
import org.netbeans.modules.csl.api.StructureItem
import org.openide.util.ImageUtilities

/**
 * K2 structure item for a Kotlin function or method declaration.
 *
 * Unlike the K1 [org.jetbrains.kotlin.structurescanner.KotlinFunctionStructureItem], this class
 * does not hold a [org.jetbrains.kotlin.resolve.BindingContext] reference. The [displayName]
 * (including receiver, parameters, and return type) is computed eagerly inside an
 * [org.jetbrains.kotlin.analysis.api.analyze] block by [KaStructureScanner] before the item is
 * constructed, making `getName()` safe to call from any thread.
 *
 * Functions are always leaf nodes in the structure tree (no children).
 *
 * @param function the PSI node for the function declaration
 * @param displayName pre-computed display string, e.g. `"receiver.name(param): ReturnType"`
 */
class KaFunctionStructureItem(
    private val function: KtNamedFunction,
    private val displayName: String
) : StructureItem {

    override fun getName() = displayName
    override fun getSortText() = function.name
    override fun getHtml(formatter: HtmlFormatter) = displayName
    override fun getElementHandle() = null
    override fun getKind() = ElementKind.METHOD
    override fun getModifiers() = emptySet<Modifier>()
    override fun isLeaf() = true
    override fun getNestedItems() = emptyList<StructureItem>()
    override fun getPosition() = function.textRange.startOffset.toLong()
    override fun getEndPosition() = function.textRange.endOffset.toLong()
    override fun getCustomIcon() =
        ImageIcon(ImageUtilities.loadImage("org/jetbrains/kotlin/completionIcons/method.png"))
}
