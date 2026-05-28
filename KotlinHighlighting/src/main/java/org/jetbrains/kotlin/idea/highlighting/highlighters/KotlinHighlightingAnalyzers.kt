// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.highlighting.highlighters

import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.idea.base.highlighting.beforeResolve.AnnotationEntryHighlightingVisitor
import org.jetbrains.kotlin.idea.base.highlighting.beforeResolve.DeclarationHighlightingVisitor
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Creates the before-resolve semantic highlighter visitors.
 *
 * These visitors operate on the PSI tree without K2 symbol resolution and cover:
 * - **Declarations**: class, property, parameter names
 *   ([DeclarationHighlightingVisitor])
 * - **Annotation entries**: the full `@AnnotationName` range
 *   ([AnnotationEntryHighlightingVisitor])
 * - **Function and type-parameter declarations**: named function and type-parameter names
 *   ([FunctionAndTypeParamHighlightingVisitor])
 *
 * The returned visitors do not require an active [KaSession] and may be used outside any
 * [org.jetbrains.kotlin.analysis.api.analyze] block.
 *
 * @param holder the [HighlightInfoHolder] that receives produced [com.intellij.codeInsight.daemon.impl.HighlightInfo] objects
 * @return array of visitors; each handles the element types relevant to its highlighting concern
 */
fun createBeforeResolveHighlightingAnalyzers(holder: HighlightInfoHolder): Array<KtVisitorVoid> = arrayOf(
    DeclarationHighlightingVisitor(holder),
    AnnotationEntryHighlightingVisitor(holder),
    FunctionAndTypeParamHighlightingVisitor(holder),
)

/**
 * Creates the K2-resolve-based semantic highlighter visitors.
 *
 * These visitors require an active [KaSession] (must be called inside an
 * [org.jetbrains.kotlin.analysis.api.analyze] block) and cover:
 * - **Function calls**: regular, extension, suspend, package-level, and operator calls
 *   ([FunctionCallHighlighter])
 * - **Type references**: class, interface, enum, annotation, type-alias, type-parameter references
 *   ([TypeHighlighter])
 * - **Variable references**: property, local variable, parameter, enum-entry references
 *   ([VariableReferenceHighlighter])
 *
 * The returned visitors capture the [KaSession] and must only be used synchronously within
 * the enclosing [org.jetbrains.kotlin.analysis.api.analyze] block.
 *
 * @param holder the [HighlightInfoHolder] that receives produced [com.intellij.codeInsight.daemon.impl.HighlightInfo] objects
 * @return array of visitors, each handling the element types relevant to its highlighting concern
 */
context(KaSession)
fun createKotlinHighlightingAnalyzers(holder: HighlightInfoHolder): Array<KtVisitorVoid> = arrayOf(
    FunctionCallHighlighter(holder),
    TypeHighlighter(holder),
    VariableReferenceHighlighter(holder),
)
