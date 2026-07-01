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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.expressionType
import org.jetbrains.kotlin.analysis.api.components.render
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggester
import org.jetbrains.kotlin.idea.k2.refactoring.introduce.K2SemanticMatcher
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Headless analysis engine for the **Introduce Constant** refactoring.
 *
 * Validates that the expression at [[startOffset]..[endOffset]] is a compile-time constant via
 * a PSI structural check (literals and constant arithmetic), finds all semantically equivalent
 * occurrences via [K2SemanticMatcher] across the whole file, and computes the available insertion
 * destinations (companion object and/or
 * top-level).
 *
 * @param ktFile        the file being refactored
 * @param startOffset   start of the selection (or caret offset when no selection)
 * @param endOffset     end of the selection (exclusive); equals [startOffset] for caret-only mode
 * @param project       IntelliJ project (for [analyze])
 */
class KaIntroduceConstantComputer(
    private val ktFile: KtFile,
    private val startOffset: Int,
    private val endOffset: Int,
    private val project: Project,
) {

    /** Result of the analysis step. */
    sealed class Outcome {
        /** The caret/selection is not on a compile-time constant expression. */
        object NotApplicable : Outcome()

        /** Analysis failed with [error]. */
        data class Error(val error: Throwable) : Outcome()

        /** Analysis succeeded; [result] holds everything the apply step needs. */
        data class Ready(val result: KaIntroduceConstantResult) : Outcome()
    }

    /**
     * Runs the full analysis and returns an [Outcome].
     *
     * This method may be called off the EDT from NetBeans' refactoring thread.
     */
    fun compute(): Outcome {
        val expression = findExpression() ?: return Outcome.NotApplicable
        return try {
            analyze(ktFile) { computeInternal(expression) }
        } catch (e: Exception) {
            Outcome.Error(e)
        }
    }

    /**
     * Finds the [KtExpression] covering [[startOffset]..[endOffset]).
     *
     * When [startOffset] == [endOffset] (caret-only), walks up to the nearest expression parent.
     */
    private fun findExpression(): KtExpression? {
        if (startOffset < endOffset) {
            val startEl = ktFile.findElementAt(startOffset) ?: return null
            val endEl = ktFile.findElementAt(endOffset - 1) ?: return null
            val common = PsiTreeUtil.findCommonParent(startEl, endEl) ?: return null
            return (common as? KtExpression)
                ?: PsiTreeUtil.getParentOfType(common, KtExpression::class.java, false)
        }
        val el = ktFile.findElementAt(startOffset) ?: return null
        return PsiTreeUtil.getParentOfType(el, KtExpression::class.java, false)
    }

    context(_: KaSession)
    private fun computeInternal(expression: KtExpression): Outcome {
        if (expression is KtDeclaration) return Outcome.NotApplicable

        // Validate that the expression is a compile-time constant via PSI structural check.
        // This avoids relying on the K2 evaluate() API which may be unavailable in standalone sessions.
        if (!isCompileTimeConstant(expression)) return Outcome.NotApplicable

        val typeText: String? = runCatching {
            expression.expressionType?.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        }.getOrNull()

        val suggestedNames: List<String> = runCatching {
            KotlinNameSuggester().suggestExpressionNames(expression).take(5)
                .map { toScreamingSnakeCase(it) }
                .toList()
        }.getOrElse { listOf("MY_CONST") }.ifEmpty { listOf("MY_CONST") }

        // Find all semantically equivalent occurrences in the file.
        val semanticMatches = runCatching {
            K2SemanticMatcher.findMatches(expression, ktFile).filterIsInstance<KtExpression>()
        }.getOrElse { listOf() }

        // K2SemanticMatcher cannot traverse KtBinaryExpression nodes that represent string
        // concatenation: KtVisitor.visitBinaryExpression flattens them to string-template
        // parts and never calls visitKtElement on the binary node itself, so findMatches
        // returns an empty list. Fall back to a structural text search in that case.
        val occurrences: List<KtExpression> = semanticMatches.ifEmpty {
            PsiTreeUtil.collectElementsOfType(ktFile, KtExpression::class.java)
                .filter { it.text == expression.text }
                .ifEmpty { listOf(expression) }
        }

        // Determine the enclosing class (skip companion object itself if expression is inside one).
        val enclosingClass = expression.parentsWithSelf
            .filterIsInstance<KtClassOrObject>()
            .firstOrNull { it !is KtObjectDeclaration || !(it as KtObjectDeclaration).isCompanion() }

        // Compute available destinations.
        val destinations = mutableListOf<ConstantDestination>()
        var companionBodyOffset: Int? = null
        var companionCreateOffset: Int? = null

        if (enclosingClass != null) {
            val classBody: KtClassBody? = enclosingClass.body
            val companion = classBody?.declarations
                ?.filterIsInstance<KtObjectDeclaration>()
                ?.firstOrNull { it.isCompanion() }

            if (companion != null) {
                val lBrace = companion.body?.lBrace
                if (lBrace != null) {
                    companionBodyOffset = lBrace.textRange.endOffset
                }
            } else {
                // No companion — we can create one; record the class body opening brace offset.
                val classLBrace = classBody?.lBrace
                if (classLBrace != null) {
                    companionCreateOffset = classLBrace.textRange.endOffset
                }
            }
            destinations += ConstantDestination.COMPANION_OBJECT
        }
        destinations += ConstantDestination.TOP_LEVEL

        // Top-level insertion: before the outermost enclosing top-level declaration (or at file end).
        val topLevelDecl = expression.parentsWithSelf
            .firstOrNull { it.parent is KtFile } as? KtExpression
        val topLevelInsertOffset = topLevelDecl?.startOffset ?: run {
            // Fall back: after the last import statement, before any top-level declarations.
            ktFile.importList?.let { it.textRange.endOffset + 1 } ?: 0
        }

        return Outcome.Ready(
            KaIntroduceConstantResult(
                expressionRange = expression.textRange,
                expressionText = expression.text,
                suggestedNames = suggestedNames,
                occurrenceRanges = occurrences.map { it.textRange },
                typeText = typeText,
                destinations = destinations,
                companionBodyOffset = companionBodyOffset,
                companionCreateOffset = companionCreateOffset,
                topLevelInsertOffset = topLevelInsertOffset,
            )
        )
    }

    companion object {
        /** Converts a camelCase name to SCREAMING_SNAKE_CASE for constant naming conventions. */
        fun toScreamingSnakeCase(name: String): String {
            if (name.isBlank()) return "MY_CONST"
            val sb = StringBuilder()
            name.forEachIndexed { i, c ->
                if (c.isUpperCase() && i > 0 && name[i - 1].isLowerCase()) sb.append('_')
                sb.append(c.uppercaseChar())
            }
            return sb.toString()
        }

        /**
         * Returns true if [expr] is a compile-time constant expression suitable for `const val`.
         *
         * Accepts: numeric/boolean/char/null literals, plain string literals (no template
         * expressions), binary arithmetic/comparison expressions between constants, unary
         * negation/not, and parenthesized constant expressions.
         */
        fun isCompileTimeConstant(expr: KtExpression): Boolean = when (expr) {
            is KtConstantExpression -> true
            is KtStringTemplateExpression -> !expr.hasInterpolation()
            is KtBinaryExpression -> {
                val l = expr.left ?: return false
                val r = expr.right ?: return false
                isCompileTimeConstant(l) && isCompileTimeConstant(r)
            }
            is KtUnaryExpression -> expr.baseExpression?.let { isCompileTimeConstant(it) } ?: false
            is KtParenthesizedExpression -> expr.expression?.let { isCompileTimeConstant(it) } ?: false
            else -> false
        }
    }
}

/** Possible destinations where the introduced constant can be placed. */
enum class ConstantDestination(val displayName: String) {
    COMPANION_OBJECT("Companion object"),
    TOP_LEVEL("Top-level of file"),
}

/**
 * All data needed by the NetBeans apply element to perform the introduce-constant transformation.
 *
 * @param expressionRange        range of the original expression in the file
 * @param expressionText         text of the expression (becomes the const val initializer)
 * @param suggestedNames         candidate constant names in SCREAMING_SNAKE_CASE, preference order
 * @param occurrenceRanges       text ranges of all semantically equivalent occurrences (unsorted)
 * @param typeText               rendered short-name type string; null if unavailable
 * @param destinations           available insertion destinations (at least one)
 * @param companionBodyOffset    offset right after `{` of existing companion object body; null = no companion
 * @param companionCreateOffset  offset right after `{` of enclosing class body; for creating new companion
 * @param topLevelInsertOffset   offset at the start of the enclosing top-level declaration
 */
data class KaIntroduceConstantResult(
    val expressionRange: TextRange,
    val expressionText: String,
    val suggestedNames: List<String>,
    val occurrenceRanges: List<TextRange>,
    val typeText: String?,
    val destinations: List<ConstantDestination>,
    val companionBodyOffset: Int?,
    val companionCreateOffset: Int?,
    val topLevelInsertOffset: Int,
)
