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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.nbm.structurescanner

import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.types.Variance
import org.netbeans.api.project.Project
import org.netbeans.modules.csl.api.StructureItem
import org.openide.filesystems.FileObject

/**
 * K2 Analysis API implementation of the Kotlin structure/outline scanner.
 *
 * This object forms the **K2 primary path** for the structure view. It runs a single
 * [analyze] block per file to eagerly compute all display strings (names, types, supertypes)
 * and then constructs [KaFunctionStructureItem], [KaPropertyStructureItem], and
 * [KaClassStructureItem] wrappers whose `getName()` is safe to call from any thread without
 * holding a [org.jetbrains.kotlin.resolve.BindingContext].
 *
 * [getStructureItems] returns `null` when no K2 session is available for the project so that
 * the caller ([org.jetbrains.kotlin.structurescanner.KotlinStructureScanner]) can fall back to
 * the K1 path transparently.
 *
 * This object belongs to the **service/model** layer and must not reference NetBeans UI APIs.
 */
object KaStructureScanner {

    /**
     * Returns the top-level [StructureItem] list for [file] using the K2 Analysis API.
     *
     * Returns `null` if no K2 session is available for [project] or if the file cannot be
     * found in the session's source module (e.g. the file has not been indexed yet).
     *
     * @param file the source Kotlin file to scan
     * @param project the owning NetBeans project
     * @return top-level structure items, or null to signal K1 fallback
     */
    fun getStructureItems(file: FileObject, project: Project): List<StructureItem>? {
        val session = KotlinAnalysisAPISession.getSession(project) ?: return null
        val kaKtFile = session.getKtFileForPath(file.path) ?: return null
        return runCatching {
            analyze(kaKtFile) {
                kaKtFile.declarations.mapNotNull { buildItem(it, isLeaf = false) }
            }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException("K2 structure scan failed for ${file.path}", e)
            null
        }
    }

    /**
     * Builds a single [StructureItem] for [decl] inside an active [KaSession].
     *
     * Returns `null` for declaration kinds that are not displayed in the structure view
     * (e.g. type aliases, destructuring declarations).
     *
     * @param decl the PSI declaration to convert
     * @param isLeaf whether the resulting item should be rendered as a leaf node
     */
    private fun KaSession.buildItem(decl: KtDeclaration, isLeaf: Boolean): StructureItem? = when (decl) {
        is KtClassOrObject -> buildClassItem(decl, isLeaf)
        is KtNamedFunction -> buildFunctionItem(decl, isLeaf)
        is KtProperty -> buildPropertyItem(decl, isLeaf)
        else -> null
    }

    /**
     * Builds a [KaFunctionStructureItem] with the full signature as the display name.
     *
     * The signature includes an optional receiver type, the function name, the value-parameter
     * list (as raw PSI text), and the return type rendered via [KaTypeRendererForSource].
     * The return type is omitted when it is `Unit`.
     */
    private fun KaSession.buildFunctionItem(fn: KtNamedFunction, @Suppress("UNUSED_PARAMETER") isLeaf: Boolean): KaFunctionStructureItem {
        val symbol = fn.symbol as? KaNamedFunctionSymbol
        val receiver = fn.receiverTypeReference?.text?.let { "$it." } ?: ""
        val params = fn.valueParameters.joinToString(prefix = "(", postfix = ")") { it.text }
        val returnType = symbol?.returnType
            ?.takeUnless { it.isUnitType }
            ?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
            ?.let { ": $it" }
            ?: ""
        val displayName = "$receiver${fn.name}$params$returnType"
        return KaFunctionStructureItem(fn, displayName)
    }

    /**
     * Builds a [KaPropertyStructureItem] with the property name and type as the display name.
     *
     * The type is taken from the PSI type reference when present; otherwise it is resolved
     * from the [KaPropertySymbol] and rendered via [KaTypeRendererForSource].
     */
    private fun KaSession.buildPropertyItem(prop: KtProperty, @Suppress("UNUSED_PARAMETER") isLeaf: Boolean): KaPropertyStructureItem {
        val symbol = prop.symbol as? KaPropertySymbol
        val type = prop.typeReference?.text?.let { ": $it" }
            ?: symbol?.returnType
                ?.takeUnless { it.isUnitType }
                ?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
                ?.let { ": $it" }
            ?: ""
        return KaPropertyStructureItem(prop, "${prop.name}$type")
    }

    /**
     * Builds a [KaClassStructureItem] with the class name, supertype list, and nested members.
     *
     * Supertype text is taken directly from PSI (no symbol resolution needed). Nested items are
     * built recursively so that the entire tree is populated before returning from the
     * [analyze] block.
     */
    private fun KaSession.buildClassItem(cls: KtClassOrObject, @Suppress("UNUSED_PARAMETER") isLeaf: Boolean): KaClassStructureItem {
        val superTypes = cls.superTypeListEntries.let { entries ->
            if (entries.isNotEmpty()) entries.joinToString(prefix = "::") { it.text } else ""
        }
        val displayName = "${cls.name}$superTypes"
        val nested = cls.declarations.mapNotNull { buildItem(it, isLeaf = true) }
        return KaClassStructureItem(cls, displayName, nested)
    }
}
