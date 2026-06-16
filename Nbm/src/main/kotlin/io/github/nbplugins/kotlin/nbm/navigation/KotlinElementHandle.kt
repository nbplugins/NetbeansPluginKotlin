/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2026 nbplugins contributors
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
package io.github.nbplugins.kotlin.nbm.navigation

import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.netbeans.modules.csl.api.ElementHandle
import org.netbeans.modules.csl.api.ElementKind
import org.netbeans.modules.csl.api.Modifier
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.csl.spi.ParserResult
import org.openide.filesystems.FileObject

/**
 * [ElementHandle] wrapping a Kotlin named declaration for use in
 * [DeclarationFinder.DeclarationLocation] and [OverridingMethods] results.
 *
 * The handle stores the declaration's name, container name, [ElementKind], modifier set,
 * source [FileObject], and character start offset so that CSL can navigate to it and
 * display it in the Find Usages / gutter-annotation UIs.
 *
 * @param name the simple name of the declaration (e.g. `"myFun"`)
 * @param containerName the name of the enclosing class, or empty for top-level declarations
 * @param kind the CSL element kind (METHOD, CLASS, PROPERTY, etc.)
 * @param modifiers the set of CSL modifiers (PUBLIC, ABSTRACT, etc.)
 * @param fileObject the source file that contains the declaration
 * @param startOffset the character start offset of the declaration within [fileObject]
 */
class KotlinElementHandle(
    private val name: String,
    private val containerName: String,
    private val kind: ElementKind,
    private val modifiers: Set<Modifier>,
    private val fileObject: FileObject,
    val startOffset: Int
) : ElementHandle {

    override fun getFileObject(): FileObject = fileObject
    override fun getMimeType(): String = "text/x-kotlin"
    override fun getName(): String = name
    override fun getIn(): String = containerName
    override fun getKind(): ElementKind = kind
    override fun getModifiers(): Set<Modifier> = modifiers

    override fun signatureEquals(handle: ElementHandle): Boolean =
        handle is KotlinElementHandle
            && name == handle.name
            && fileObject == handle.fileObject
            && startOffset == handle.startOffset

    override fun getOffsetRange(result: ParserResult): OffsetRange =
        OffsetRange(startOffset, startOffset + name.length)

    companion object {
        /**
         * Creates a [KotlinElementHandle] from a [KtNamedDeclaration] PSI node.
         *
         * The [ElementKind] is inferred from the PSI type:
         * [KtNamedFunction] → METHOD, [KtClassOrObject] → CLASS, [KtProperty] → FIELD.
         *
         * @param psi the PSI declaration node
         * @param fo the source file that contains [psi]
         * @return a new [KotlinElementHandle]
         */
        fun fromPsi(psi: KtNamedDeclaration, fo: FileObject): KotlinElementHandle {
            val kind = when (psi) {
                is KtNamedFunction -> ElementKind.METHOD
                is KtClassOrObject -> ElementKind.CLASS
                is KtProperty -> ElementKind.FIELD
                else -> ElementKind.OTHER
            }
            val container = (psi.parent as? KtClassOrObject)?.name ?: ""
            return KotlinElementHandle(
                name = psi.name ?: "",
                containerName = container,
                kind = kind,
                modifiers = emptySet(),
                fileObject = fo,
                startOffset = psi.textRange.startOffset
            )
        }
    }
}
