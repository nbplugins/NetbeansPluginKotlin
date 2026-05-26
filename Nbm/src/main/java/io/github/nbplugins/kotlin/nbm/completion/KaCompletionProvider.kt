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

package io.github.nbplugins.kotlin.nbm.completion

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.isTopLevel
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.KotlinImageProvider
import org.netbeans.modules.csl.api.ElementKind

/**
 * K2 Analysis API primary completion path.
 *
 * Returns [KaCompletionItem]s for all visible declarations at [caretOffset] in [kaKtFile] whose
 * name starts with [prefix] (case-insensitive). Items include pre-rendered signatures and icons,
 * computed inside the `analyze {}` block so that K2 symbol lifetimes are respected.
 *
 * After-dot completion uses the receiver expression's type scope directly, so only members and
 * applicable extensions of that type are offered — matching IDEA's K2 completion behaviour.
 *
 * Must be called with a [KtFile] owned by a [io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession].
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs
 * other than icon loading via [KotlinImageProvider].
 */
object KaCompletionProvider {

    /**
     * Returns completion items for declarations visible at [caretOffset] in [kaKtFile] that
     * match [prefix].
     *
     * @param kaKtFile    K2-session-owned [KtFile] for the file being edited
     * @param caretOffset document offset of the caret
     * @param prefix      identifier prefix to filter by (case-insensitive); empty string returns all
     * @param isAfterDot  `true` when triggered after a dot receiver (e.g. `expr.|`); uses the
     *                    receiver type's member scope instead of the full lexical scope context
     * @return filtered list of [KaCompletionItem]s, or an empty list if analysis fails
     */
    fun getItemsAt(kaKtFile: KtFile, caretOffset: Int, prefix: String,
                   isAfterDot: Boolean = false): List<KaCompletionItem> =
        runCatching {
            analyze(kaKtFile) {
                val psi = kaKtFile.findElementAt(caretOffset)
                    ?: kaKtFile.findElementAt((caretOffset - 1).coerceAtLeast(0))
                    ?: return@analyze emptyList()
                val ktElement = generateSequence(psi) { it.parent }
                    .filterIsInstance<KtElement>()
                    .firstOrNull() ?: return@analyze emptyList()

                if (isAfterDot) {
                    itemsAfterDot(kaKtFile, ktElement, prefix)
                } else {
                    itemsFromScopeContext(kaKtFile, ktElement, prefix)
                }
            }
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logWarning(
                "K2 completion failed for ${kaKtFile.virtualFile?.path} at $caretOffset: $e"
            )
        }.getOrDefault(emptyList())

    /**
     * Collects completions after a dot receiver using the receiver expression's type scope.
     *
     * Non-extension members of the receiver type are collected from [KaType.scope]; applicable
     * extension callables (those whose receiver type is a supertype of the actual receiver) are
     * collected from the lexical scope context.
     */
    private fun KaSession.itemsAfterDot(
        kaKtFile: KtFile, ktElement: KtElement, prefix: String
    ): List<KaCompletionItem> {
        val receiverExpr = generateSequence(ktElement as com.intellij.psi.PsiElement) { it.parent }
            .filterIsInstance<KtQualifiedExpression>()
            .firstOrNull()
            ?.receiverExpression ?: return emptyList()

        val receiverType = receiverExpr.expressionType
            ?.takeIf { it !is KaErrorType } ?: return emptyList()

        val seen = mutableSetOf<String>()
        val items = mutableListOf<KaCompletionItem>()

        // 1. Non-extension members of the receiver type (TypeScope)
        receiverType.scope?.declarationScope?.declarations
            ?.filter { sym ->
                prefixMatches(sym, prefix) &&
                sym is KaCallableSymbol && !sym.isExtension
            }
            ?.mapNotNull { sym -> toItem(sym, basePriority = 1) }
            ?.forEach { item ->
                if (seen.add("${item.name}|${item.signature}")) items.add(item)
            }

        // 2. Extension callables from the lexical scope applicable to the receiver type
        for (scopeWithKind in kaKtFile.scopeContext(ktElement).scopes) {
            val basePriority = scopeBasePriority(scopeWithKind.kind)
            scopeWithKind.scope.declarations
                .filter { sym ->
                    prefixMatches(sym, prefix) &&
                    sym is KaCallableSymbol &&
                    sym.isExtension &&
                    sym.receiverType?.let { receiverType.isSubtypeOf(it) } == true
                }
                .mapNotNull { sym -> toItem(sym, basePriority) }
                .forEach { item ->
                    if (seen.add("${item.name}|${item.signature}")) items.add(item)
                }
        }

        return items
    }

    /**
     * Collects completions from the full lexical scope context (non-dot case).
     *
     * Iterates scopes in tower order (innermost first) and deduplicates by (name, signature) so
     * that symbols visible through multiple scope layers appear only once.
     */
    private fun KaSession.itemsFromScopeContext(
        kaKtFile: KtFile, ktElement: KtElement, prefix: String
    ): List<KaCompletionItem> {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<KaCompletionItem>()
        for (scopeWithKind in kaKtFile.scopeContext(ktElement).scopes) {
            val basePriority = scopeBasePriority(scopeWithKind.kind)
            scopeWithKind.scope.declarations
                .filter { sym -> prefixMatches(sym, prefix) }
                .mapNotNull { sym -> toItem(sym, basePriority) }
                .forEach { item ->
                    if (seen.add("${item.name}|${item.signature}")) items.add(item)
                }
        }
        return items
    }

    private fun prefixMatches(sym: KaDeclarationSymbol, prefix: String): Boolean {
        if (prefix.isEmpty()) return true
        return (sym as? KaNamedSymbol)?.name?.identifier
            ?.startsWith(prefix, ignoreCase = true) == true
    }

    /**
     * Maps [KaScopeKind] to a base priority integer, matching IDEA's [CallableKind] ordering:
     * locals first, then type members, then package/import scope.
     * Lower value = higher position in the completion list.
     */
    private fun scopeBasePriority(kind: KaScopeKind): Int = when (kind) {
        is KaScopeKind.LocalScope                   -> 0
        is KaScopeKind.TypeScope                    -> 1
        is KaScopeKind.StaticMemberScope            -> 2
        is KaScopeKind.TypeParameterScope           -> 3
        is KaScopeKind.PackageMemberScope,
        is KaScopeKind.ScriptMemberScope            -> 4
        is KaScopeKind.ExplicitSimpleImportingScope -> 5
        is KaScopeKind.DefaultSimpleImportingScope  -> 6
        is KaScopeKind.ExplicitStarImportingScope   -> 7
        is KaScopeKind.DefaultStarImportingScope    -> 8
        else                                        -> 9
    }

    private fun KaSession.toItem(sym: KaDeclarationSymbol, basePriority: Int): KaCompletionItem? {
        val name = (sym as? KaNamedSymbol)?.name?.identifier ?: return null
        val (kind, icon, isFunctionLike) = when (sym) {
            is KaNamedFunctionSymbol -> Triple(
                ElementKind.METHOD,
                when {
                    sym.isExtension  -> KotlinImageProvider.extensionFunctionImage
                    sym.isSuspend && !sym.isTopLevel -> KotlinImageProvider.suspendFunctionImage
                    sym.isTopLevel   -> KotlinImageProvider.functionImage
                    else             -> KotlinImageProvider.methodImage
                },
                true,
            )
            is KaFunctionSymbol -> Triple(ElementKind.METHOD, KotlinImageProvider.functionImage, true)
            is KaValueParameterSymbol -> Triple(ElementKind.PARAMETER, KotlinImageProvider.parameterImage, false)
            is KaLocalVariableSymbol -> Triple(
                ElementKind.VARIABLE,
                if (sym.isVal) KotlinImageProvider.valImage else KotlinImageProvider.varImage,
                false,
            )
            is KaPropertySymbol -> Triple(
                ElementKind.FIELD,
                if (sym.isVal) KotlinImageProvider.valImage else KotlinImageProvider.varImage,
                false,
            )
            is KaTypeAliasSymbol -> Triple(ElementKind.CLASS, KotlinImageProvider.typeAliasImage, false)
            is KaClassSymbol -> Triple(
                ElementKind.CLASS,
                when (sym.classKind) {
                    KaClassKind.INTERFACE            -> KotlinImageProvider.interfaceImage
                    KaClassKind.ENUM_CLASS           -> KotlinImageProvider.enumImage
                    KaClassKind.ANNOTATION_CLASS     -> KotlinImageProvider.annotationImage
                    KaClassKind.OBJECT,
                    KaClassKind.COMPANION_OBJECT,
                    KaClassKind.ANONYMOUS_OBJECT     -> KotlinImageProvider.objectImage
                    else                             -> KotlinImageProvider.classKotlinImage
                },
                false,
            )
            else -> Triple(ElementKind.OTHER, null, false)
        }
        val symbolPriority = when (kind) {
            ElementKind.FIELD  -> 0
            ElementKind.METHOD -> 1
            ElementKind.CLASS  -> 2
            else               -> 9
        }
        return KaCompletionItem(name, kind, isFunctionLike, buildSignature(sym), icon,
                                basePriority * 10 + symbolPriority)
    }

    private fun KaSession.buildSignature(sym: KaDeclarationSymbol): String = when (sym) {
        is KaFunctionSymbol -> {
            val params = sym.valueParameters.joinToString(", ", "(", ")") { p ->
                buildString {
                    if (p.isVararg) append("vararg ")
                    append(p.name.identifier)
                    append(": ")
                    append(p.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT))
                    if (p.hasDefaultValue) append(" = ...")
                }
            }
            "$params: ${sym.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)}"
        }
        is KaPropertySymbol ->
            ": ${sym.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)}"
        else -> ""
    }
}
