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
 * [getStructureItems] returns `null` when no K2 session is available for the project; the caller
 * ([KotlinStructureScanner]) then shows an empty tree.
 *
 * Individual declarations are converted defensively: if K2 resolution fails for one declaration
 * (for example a defect in the bundled analysis engine), only that declaration degrades to a
 * PSI-only name instead of emptying the whole tree.
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
     * @return top-level structure items, or `null` when the K2 session is unavailable
     */
    fun getStructureItems(file: FileObject, project: Project): List<StructureItem>? {
        val session = KotlinAnalysisAPISession.getSession(project) ?: return null
        val kaKtFile = session.getKtFileForPath(file.path) ?: return null
        return runCatching {
            analyze(kaKtFile) {
                kaKtFile.declarations.mapNotNull { buildItemSafely(it, isLeaf = false, file) }
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
    private fun KaSession.buildItem(decl: KtDeclaration, isLeaf: Boolean, file: FileObject): StructureItem? = when (decl) {
        is KtClassOrObject -> buildClassItem(decl, isLeaf, file)
        is KtNamedFunction -> buildFunctionItem(decl, isLeaf, file)
        is KtProperty -> buildPropertyItem(decl, isLeaf, file)
        else -> null
    }

    /**
     * Builds an item for [decl], degrading to a PSI-only item when analysis fails.
     *
     * K2 resolution of a single declaration can fail for reasons unrelated to the rest of the
     * file — for example a `NoSuchMethodError` inside the bundled analysis engine when a Java
     * library supertype is involved. Without this guard one such failure aborts the whole
     * [analyze] block and leaves the Navigator panel empty, hiding every other declaration.
     * The failure is logged and the declaration is still shown using PSI data only (name,
     * parameters, declared type references, supertype names) so the tree stays usable.
     *
     * @param decl the PSI declaration to convert
     * @param isLeaf whether the resulting item should be rendered as a leaf node
     */
    private fun KaSession.buildItemSafely(decl: KtDeclaration, isLeaf: Boolean, file: FileObject): StructureItem? =
        runCatching { buildItem(decl, isLeaf, file) }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException(
                "K2 structure item failed for '${decl.name ?: decl}' in ${file.path}; using PSI-only fallback",
                e
            )
            buildPsiOnlyItem(decl, file)
        }

    /**
     * Builds an item for [decl] from PSI alone, without touching K2 symbols or types.
     *
     * Used as a fallback by [buildItemSafely] when resolving a declaration fails. Nested
     * declarations are converted recursively with the same PSI-only rules.
     *
     * @param decl the PSI declaration to convert
     */
    private fun buildPsiOnlyItem(decl: KtDeclaration, file: FileObject): StructureItem? = when (decl) {
        is KtClassOrObject -> KaClassStructureItem(
            decl,
            classDisplayName(decl),
            decl.declarations.mapNotNull { buildPsiOnlyItem(it, file) },
            file
        )
        is KtNamedFunction -> KaFunctionStructureItem(decl, functionDisplayName(decl, returnType = null), file)
        is KtProperty -> KaPropertyStructureItem(decl, propertyDisplayName(decl, decl.typeReference?.text), file)
        else -> null
    }

    /**
     * Builds the display name of [fn] from PSI data.
     *
     * @param fn the function declaration
     * @param returnType rendered return type, or `null` to omit it
     */
    private fun functionDisplayName(fn: KtNamedFunction, returnType: String?): String {
        val receiver = fn.receiverTypeReference?.text?.let { "$it." } ?: ""
        val params = fn.valueParameters.joinToString(prefix = "(", postfix = ")") { it.text }
        val suffix = returnType?.let { ": $it" } ?: ""
        return "$receiver${fn.name}$params$suffix"
    }

    /**
     * Builds the display name of [prop] from PSI data.
     *
     * @param prop the property declaration
     * @param type declared or rendered type text, or `null` to omit it
     */
    private fun propertyDisplayName(prop: KtProperty, type: String?): String {
        val suffix = type?.let { ": $it" } ?: ""
        return "${prop.name}$suffix"
    }

    /**
     * Builds the display name of [cls] from PSI data.
     *
     * @param cls the class or object declaration
     */
    private fun classDisplayName(cls: KtClassOrObject): String {
        val superTypes = cls.superTypeListEntries.let { entries ->
            if (entries.isNotEmpty()) entries.joinToString(prefix = "::") { it.text } else ""
        }
        return "${cls.name}$superTypes"
    }

    /**
     * Builds a [KaFunctionStructureItem] with the full signature as the display name.
     *
     * The signature includes an optional receiver type, the function name, the value-parameter
     * list (as raw PSI text), and the return type rendered via [KaTypeRendererForSource].
     * The return type is omitted when it is `Unit`.
     */
    private fun KaSession.buildFunctionItem(fn: KtNamedFunction, @Suppress("UNUSED_PARAMETER") isLeaf: Boolean, file: FileObject): KaFunctionStructureItem {
        val symbol = fn.symbol as? KaNamedFunctionSymbol
        val returnType = resolveReturnType("function", fn.name, file) {
            symbol?.returnType
                ?.takeUnless { it.isUnitType }
                ?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        }
        return KaFunctionStructureItem(fn, functionDisplayName(fn, returnType), file)
    }

    /**
     * Builds a [KaPropertyStructureItem] with the property name and type as the display name.
     *
     * The type is taken from the PSI type reference when present; otherwise it is resolved
     * from the [KaPropertySymbol] and rendered via [KaTypeRendererForSource].
     */
    private fun KaSession.buildPropertyItem(prop: KtProperty, @Suppress("UNUSED_PARAMETER") isLeaf: Boolean, file: FileObject): KaPropertyStructureItem {
        val declaredType = prop.typeReference?.text
        val type = declaredType ?: resolveReturnType("property", prop.name, file) {
            (prop.symbol as? KaPropertySymbol)?.returnType
                ?.takeUnless { it.isUnitType }
                ?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        }
        return KaPropertyStructureItem(prop, propertyDisplayName(prop, type), file)
    }

    /**
     * Evaluates [compute], returning `null` and logging the failure instead of propagating it.
     *
     * Type rendering walks into supertypes and library Java classes, which is where defects in
     * the bundled analysis engine surface. A failure there must not hide the declaration from
     * the structure view — the name and PSI-declared type are still valid.
     *
     * @param kind human-readable declaration kind used in the log message
     * @param name declaration name used in the log message
     * @param file file being scanned, used in the log message
     * @param compute type-rendering computation to evaluate
     */
    private fun resolveReturnType(kind: String, name: String?, file: FileObject, compute: () -> String?): String? =
        runCatching { compute() }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException(
                "K2 structure type resolution failed for $kind '${name ?: "?"}' in ${file.path}; omitting type",
                e
            )
            null
        }

    /**
     * Builds a [KaClassStructureItem] with the class name, supertype list, and nested members.
     *
     * Supertype text is taken directly from PSI (no symbol resolution needed). Nested items are
     * built recursively so that the entire tree is populated before returning from the
     * [analyze] block.
     */
    private fun KaSession.buildClassItem(cls: KtClassOrObject, @Suppress("UNUSED_PARAMETER") isLeaf: Boolean, file: FileObject): KaClassStructureItem {
        val nested = cls.declarations.mapNotNull { buildItemSafely(it, isLeaf = true, file) }
        return KaClassStructureItem(cls, classDisplayName(cls), nested, file)
    }
}
