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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.nbm.completion

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.highlighter.KaHighlightColorMapper
import io.github.nbplugins.kotlin.nbm.highlighter.KotlinColorResolver
import io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.declarations.impl.KaDeclarationRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.types.Variance
import org.netbeans.api.project.Project
import org.openide.filesystems.FileObject

/**
 * Assembles a rich HTML documentation popup for a Kotlin symbol.
 *
 * Used by [org.jetbrains.kotlin.completion.KotlinCodeCompletionHandler.documentElement] to
 * provide hover tooltip content.  The popup mirrors the IDEA hover tooltip structure:
 * 1. Syntax-highlighted signature (semantic colors from the active user theme)
 * 2. Container info ("in ClassName" or "in package.name")
 * 3. KDoc description and structured sections (@param, @return, @throws, @since)
 *
 * All colors are resolved at runtime from the active NetBeans theme via [KotlinColorResolver],
 * so the popup adapts automatically to light and dark themes.
 *
 * This object belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 */
object KtDocumentationRenderer {

    /**
     * Builds a rich HTML documentation string for the Kotlin symbol referenced at [usageOffset]
     * in [file], running a K2 analysis session for [project].
     *
     * Returns `null` when:
     * - No K2 session is available for [project]
     * - [file] is not indexed in the session
     * - There is no [KtReferenceExpression] at [usageOffset]
     * - The reference does not resolve to a [KaDeclarationSymbol]
     *
     * @param project     the NetBeans project owning [file]
     * @param file        the source file containing the reference
     * @param usageOffset character offset of the identifier start in [file]
     * @return an HTML string for the documentation popup, or `null`
     */
    fun buildHtml(project: Project, file: FileObject, usageOffset: Int): String? =
        runCatching {
            val session = KotlinAnalysisAPISession.getSession(project) ?: return null
            val kaKtFile = session.getKtFileForPath(file.path) ?: return null
            val kaElement = kaKtFile.findElementAt(usageOffset) ?: return null
            val kaRef = PsiTreeUtil.getNonStrictParentOfType(kaElement, KtReferenceExpression::class.java)
                ?: return null
            analyze(kaKtFile) {
                val symbol = kaRef.mainReference?.resolveToSymbol() as? KaDeclarationSymbol
                    ?: return@analyze null
                val declarationPsi = (kaRef.mainReference?.resolve() as? KtDeclaration)
                    ?: (symbol.psi as? KtDeclaration)
                buildHtmlForSymbol(symbol, declarationPsi)
            }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException("K2 documentation rendering failed", e)
            null
        }

    /**
     * Assembles the full HTML popup body for [symbol] inside an active K2 `analyze {}` block.
     *
     * Layout: `<pre>` block with the syntax-highlighted signature, optional container line,
     * then a `<hr>` divider and the KDoc body when KDoc is present.
     *
     * @param symbol         the resolved K2 declaration symbol
     * @param declarationPsi the PSI element of the declaration, used for KDoc lookup;
     *                       obtained via direct reference resolution or [KaSymbol.psi]
     * @return an HTML string starting with `<html><body>`
     */
    private fun KaSession.buildHtmlForSymbol(symbol: KaDeclarationSymbol, declarationPsi: KtDeclaration?): String {
        val signatureHtml = renderSignatureHtml(symbol)
        val containerHtml = buildContainerHtml(symbol)
        val kdocHtml = declarationPsi?.let { buildKDocHtml(it) }
        return buildString {
            append("<html><body>")
            append("<pre style='margin:0;padding:4px;font-family:monospace'>")
            append(signatureHtml)
            append("</pre>")
            if (containerHtml != null) {
                append("<div style='color:#808080;font-size:90%;padding:0 4px'>")
                append(containerHtml.htmlEscape())
                append("</div>")
            }
            if (kdocHtml != null) {
                append("<hr style='margin:4px 0'>")
                append(kdocHtml)
            }
            append("</body></html>")
        }
    }

    /**
     * Builds the syntax-highlighted HTML signature for [symbol].
     *
     * For named functions, properties, constructors, and classes the signature is assembled
     * directly from K2 symbol components so each part (keyword, name, parameters, types)
     * receives its individual semantic color.  For other symbol kinds the K2 plain-text
     * renderer is used as a fallback and the result is HTML-escaped.
     *
     * Must be called inside an active K2 `analyze {}` block.
     *
     * @param symbol the declaration symbol to render
     * @return an HTML fragment (no outer tags) with inline `style="color:..."` spans
     */
    private fun KaSession.renderSignatureHtml(symbol: KaDeclarationSymbol): String = buildString {
        val kwColor = KotlinColorResolver.foregroundHex("KEYWORD")

        fun kw(word: String) {
            if (kwColor != null) append("<span style=\"color:$kwColor\">$word</span>")
            else append(word.htmlEscape())
        }

        fun str(text: String) { append(text.htmlEscape()) }

        fun renderType(type: KaType) {
            str(type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT))
        }

        fun nameSpan(name: Name?, sym: KaDeclarationSymbol) {
            val text = name?.asString() ?: return
            val color = KaHighlightColorMapper.colorCategoryForDeclaration(sym)
                ?.let { KotlinColorResolver.foregroundHex(it) }
            if (color != null) append("<span style=\"color:$color\">${text.htmlEscape()}</span>")
            else str(text)
        }

        fun typeParamHtml(params: List<KaTypeParameterSymbol>) {
            if (params.isEmpty()) return
            str("<")
            params.forEachIndexed { i, tp ->
                if (i > 0) str(", ")
                nameSpan(tp.name, tp)
            }
            str(">")
        }

        fun valueParamHtml(params: List<KaValueParameterSymbol>) {
            str("(")
            params.forEachIndexed { i, p ->
                if (i > 0) str(", ")
                nameSpan(p.name, p)
                str(": ")
                renderType(p.returnType)
                if (p.hasDefaultValue) str(" = ...")
            }
            str(")")
        }

        when (symbol) {
            is KaNamedFunctionSymbol -> {
                if (symbol.isSuspend) { kw("suspend"); append(" ") }
                kw("fun"); append(" ")
                if (symbol.typeParameters.isNotEmpty()) {
                    typeParamHtml(symbol.typeParameters)
                    append(" ")
                }
                nameSpan(symbol.name, symbol)
                valueParamHtml(symbol.valueParameters)
                if (!symbol.returnType.isUnitType) {
                    str(": ")
                    renderType(symbol.returnType)
                }
            }
            is KaConstructorSymbol -> {
                kw("constructor")
                valueParamHtml(symbol.valueParameters)
            }
            is KaPropertySymbol -> {
                kw(if (symbol.isVal) "val" else "var"); append(" ")
                nameSpan(symbol.name, symbol)
                str(": ")
                renderType(symbol.returnType)
            }
            is KaNamedClassSymbol -> {
                val classKw = when (symbol.classKind) {
                    KaClassKind.INTERFACE -> "interface"
                    KaClassKind.OBJECT,
                    KaClassKind.COMPANION_OBJECT,
                    KaClassKind.ANONYMOUS_OBJECT -> "object"
                    KaClassKind.ENUM_CLASS -> "enum class"
                    KaClassKind.ANNOTATION_CLASS -> "annotation class"
                    else -> when (symbol.modality) {
                        KaSymbolModality.ABSTRACT -> "abstract class"
                        KaSymbolModality.SEALED -> "sealed class"
                        else -> "class"
                    }
                }
                kw(classKw); append(" ")
                nameSpan(symbol.name, symbol)
                typeParamHtml(symbol.typeParameters)
            }
            is KaClassSymbol -> {
                val classKw = when (symbol.classKind) {
                    KaClassKind.INTERFACE -> "interface"
                    KaClassKind.OBJECT,
                    KaClassKind.COMPANION_OBJECT,
                    KaClassKind.ANONYMOUS_OBJECT -> "object"
                    KaClassKind.ENUM_CLASS -> "enum class"
                    KaClassKind.ANNOTATION_CLASS -> "annotation class"
                    else -> "class"
                }
                kw(classKw)
                // anonymous/unnamed class — no name or type parameters to render
            }
            is KaTypeAliasSymbol -> {
                kw("typealias"); append(" ")
                nameSpan(symbol.name, symbol)
            }
            is KaEnumEntrySymbol -> {
                nameSpan(symbol.name, symbol)
            }
            else -> {
                append(symbol.render(KaDeclarationRendererForSource.WITH_SHORT_NAMES).htmlEscape())
            }
        }
    }

    /**
     * Returns the "in ClassName" or "in package.name" container description for [symbol].
     *
     * Returns `null` when the symbol is at the top level of a root package or container
     * information is unavailable.
     *
     * Must be called inside an active K2 `analyze {}` block.
     *
     * @param symbol the declaration symbol
     * @return a plain-text container description such as `"in MyClass"`, or `null`
     */
    private fun KaSession.buildContainerHtml(symbol: KaDeclarationSymbol): String? {
        val parent = symbol.containingDeclaration
        if (parent is KaClassSymbol) {
            return "in ${parent.name?.asString() ?: ""}"
        }
        if (symbol is KaCallableSymbol) {
            val pkgName = symbol.callableId?.packageName?.takeIf { !it.isRoot }?.asString()
            if (pkgName != null) return "in $pkgName"
        }
        if (symbol is KaClassLikeSymbol) {
            val pkgName = symbol.classId?.packageFqName?.takeIf { !it.isRoot }?.asString()
            if (pkgName != null) return "in $pkgName"
        }
        return null
    }

    /**
     * Finds the [KDoc] comment for [psi], checking both children of the declaration
     * (standard K2 PSI structure) and immediately preceding siblings (fallback).
     *
     * @param psi the declaration whose KDoc to locate
     * @return the [KDoc] element, or `null` if none is present
     */
    private fun locateKDoc(psi: KtDeclaration): KDoc? {
        // Standard: KDoc is the first child of the declaration in Kotlin PSI
        PsiTreeUtil.findChildOfType(psi, KDoc::class.java)?.let { return it }
        // Fallback: KDoc may be a preceding sibling separated only by whitespace
        var candidate = psi.prevSibling
        while (candidate != null) {
            if (candidate is KDoc) return candidate
            if (candidate !is com.intellij.psi.PsiWhiteSpace && candidate !is com.intellij.psi.PsiComment) break
            candidate = candidate.prevSibling
        }
        return null
    }

    /**
     * Builds an HTML fragment for the KDoc comment of [symbol].
     *
     * Renders the description paragraph and structured sections:
     * - `@param` entries in a compact table
     * - `@return` as a labeled paragraph
     * - `@throws` / `@exception` entries
     * - `@since` as an italicized line
     *
     * Inline code (`` `code` ``) is rendered as `<code>`, bold (`**text**`) as `<b>`.
     * Returns `null` when the symbol has no KDoc.
     *
     * Must be called inside an active K2 `analyze {}` block.
     *
     * @param symbol the declaration symbol whose KDoc to render
     * @return an HTML fragment, or `null` if no KDoc is present
     */
    private fun buildKDocHtml(psi: KtDeclaration): String? {
        val kdoc = locateKDoc(psi) ?: return null
        val allSections = kdoc.getAllSections().toList()
        return buildString {
            append("<div style='padding:4px'>")
            val description = kdoc.getDefaultSection().getContent().trim()
            if (description.isNotEmpty()) {
                append("<p style='margin:0'>")
                append(description.toKDocHtml())
                append("</p>")
            }
            val paramSections = allSections.filter { it.getName() == "param" }
            if (paramSections.isNotEmpty()) {
                append("<table style='margin-top:4px;border-spacing:0'>")
                for (section in paramSections) {
                    val paramName = section.getSubjectName() ?: continue
                    val paramDesc = section.getContent().trim()
                    append("<tr><td style='color:#808080;padding-right:8px;white-space:nowrap'>")
                    append("<code>${paramName.htmlEscape()}</code>")
                    append("</td><td>")
                    append(paramDesc.toKDocHtml())
                    append("</td></tr>")
                }
                append("</table>")
            }
            val returnSection = allSections.find { it.getName() == "return" }
            if (returnSection != null) {
                val content = returnSection.getContent().trim()
                if (content.isNotEmpty()) {
                    append("<p style='margin-top:4px'><b>Returns:</b> ")
                    append(content.toKDocHtml())
                    append("</p>")
                }
            }
            val throwsSections = allSections.filter {
                it.getName() == "throws" || it.getName() == "exception"
            }
            for (section in throwsSections) {
                val typeName = section.getSubjectName() ?: continue
                val throwsDesc = section.getContent().trim()
                append("<p style='margin-top:4px'><b>Throws</b> <code>${typeName.htmlEscape()}</code>")
                if (throwsDesc.isNotEmpty()) {
                    append(" — ")
                    append(throwsDesc.toKDocHtml())
                }
                append("</p>")
            }
            val sinceSection = allSections.find { it.getName() == "since" }
            if (sinceSection != null) {
                val content = sinceSection.getContent().trim()
                if (content.isNotEmpty()) {
                    append("<p style='margin-top:4px;color:#808080'><i>Since: ${content.htmlEscape()}</i></p>")
                }
            }
            append("</div>")
        }
    }

    /** HTML-escapes `&`, `<`, and `>`. */
    internal fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Converts basic KDoc inline markup to HTML.
     *
     * Escapes HTML first, then applies:
     * - `` `code` `` → `<code>code</code>`
     * - `**bold**` → `<b>bold</b>`
     *
     * @return the string with inline markup converted to HTML tags
     */
    internal fun String.toKDocHtml(): String =
        htmlEscape()
            .replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
            .replace(Regex("\\*\\*([^*]+)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
}
