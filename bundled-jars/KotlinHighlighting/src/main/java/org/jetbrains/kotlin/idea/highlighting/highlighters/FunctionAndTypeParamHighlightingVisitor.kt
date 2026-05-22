// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.highlighting.highlighters

import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import org.jetbrains.kotlin.idea.highlighter.KotlinHighlightInfoTypeSemanticNames
import org.jetbrains.kotlin.idea.highlighter.visitor.AbstractHighlightingVisitor
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeParameter

/**
 * Before-resolve visitor that highlights named function and type-parameter declarations.
 *
 * This is a minimal subset of [org.jetbrains.kotlin.idea.highlighter.BeforeResolveHighlightingVisitor],
 * which cannot be compiled here because it imports [org.jetbrains.kotlin.idea.KotlinLanguage] — an
 * IDEA-plugin-internal class absent from published Maven artifacts. Only the declaration-site
 * highlighting for functions and type parameters is needed; keyword and label highlights are
 * produced by the lexer-based syntax highlighter and are out of scope for semantic highlighting.
 *
 * @param holder the [HighlightInfoHolder] that receives produced [com.intellij.codeInsight.daemon.impl.HighlightInfo] objects
 */
internal class FunctionAndTypeParamHighlightingVisitor(holder: HighlightInfoHolder) : AbstractHighlightingVisitor(holder) {

    /**
     * Highlights the name identifier of a named function with [KotlinHighlightInfoTypeSemanticNames.FUNCTION_DECLARATION].
     *
     * @param function the named function declaration to highlight
     */
    override fun visitNamedFunction(function: KtNamedFunction) {
        highlightNamedDeclaration(function, KotlinHighlightInfoTypeSemanticNames.FUNCTION_DECLARATION)
        super.visitNamedFunction(function)
    }

    /**
     * Highlights the name identifier of a type parameter with [KotlinHighlightInfoTypeSemanticNames.TYPE_PARAMETER].
     *
     * @param parameter the type parameter to highlight
     */
    override fun visitTypeParameter(parameter: KtTypeParameter) {
        parameter.nameIdentifier?.let { highlightName(it, KotlinHighlightInfoTypeSemanticNames.TYPE_PARAMETER) }
        super.visitTypeParameter(parameter)
    }
}
