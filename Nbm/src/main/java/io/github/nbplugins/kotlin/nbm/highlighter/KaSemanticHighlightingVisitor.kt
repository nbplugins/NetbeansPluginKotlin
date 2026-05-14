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
package io.github.nbplugins.kotlin.nbm.highlighter

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.highlighter.semanticanalyzer.KotlinHighlightingAttributes
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.netbeans.modules.csl.api.ColoringAttributes
import org.netbeans.modules.csl.api.OffsetRange

/**
 * Computes semantic highlighting ranges for a K2-owned [KtFile] using the K2 Analysis API.
 *
 * Runs a single [analyze] block that covers both symbol-based token colouring for all AST
 * nodes and deprecated-element highlighting derived from K2 diagnostics.  The result is a
 * map from text offset ranges to their [ColoringAttributes] sets, suitable for passing
 * directly to [org.netbeans.modules.csl.api.SemanticAnalyzer.getHighlights].
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 *
 * @param kaKtFile the K2-session-owned [KtFile] to highlight; must have been obtained from
 *   [io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession.getKtFileForPath]
 */
class KaSemanticHighlightingVisitor(private val kaKtFile: KtFile) {

    /**
     * Runs K2 analysis on [kaKtFile] and returns the full set of semantic highlight ranges.
     *
     * Uses two [analyze] calls:
     * - the first visits the AST for symbol-based highlights (class, interface, property, etc.)
     * - the second collects deprecated-element highlights; wrapped in [runCatching] because K2
     *   2.0.21 [collectDiagnostics] throws [IllegalArgumentException] for some FIR elements
     *   (K2 bug: `FirIncompatibleClassExpressionChecker` requires non-null source).
     *   Symbol highlights are never affected by deprecated-collection failures.
     *
     * @return map from [OffsetRange] to the corresponding [ColoringAttributes] set
     */
    fun computeHighlightingRanges(): Map<OffsetRange, Set<ColoringAttributes>> {
        val positions = hashMapOf<OffsetRange, Set<ColoringAttributes>>()
        analyze(kaKtFile) {
            visitKtFile(positions)
        }
        runCatching {
            analyze(kaKtFile) {
                highlightDeprecated(positions)
            }
        }
        return positions
    }

    /**
     * Visits every child of [kaKtFile] with a [KtVisitorVoid] that dispatches to typed
     * highlight helpers operating inside the current [KaSession].
     *
     * @param positions accumulator for highlight ranges
     */
    context(KaSession)
    private fun visitKtFile(positions: HashMap<OffsetRange, Set<ColoringAttributes>>) {
        kaKtFile.acceptChildren(object : KtVisitorVoid() {
            override fun visitElement(element: PsiElement) = element.acceptChildren(this)

            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                highlightSimpleName(expression, positions)
                super.visitSimpleNameExpression(expression)
            }

            override fun visitTypeParameter(parameter: KtTypeParameter) {
                val identifier = parameter.nameIdentifier
                if (identifier != null) {
                    positions.highlight(KotlinHighlightingAttributes.TYPE_PARAMETER, identifier.textRange)
                }
                super.visitTypeParameter(parameter)
            }

            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                val identifier = classOrObject.nameIdentifier
                if (identifier == null) {
                    classOrObject.acceptChildren(this)
                    return
                }
                val symbol = classOrObject.symbol as? KaClassSymbol
                if (symbol != null) {
                    highlightClassSymbol(identifier, symbol, positions)
                }
                super.visitClassOrObject(classOrObject)
            }

            override fun visitProperty(property: KtProperty) {
                val identifier = property.nameIdentifier ?: return super.visitProperty(property)
                val symbol = property.symbol
                if (symbol is KaPropertySymbol) {
                    highlightPropertySymbol(identifier, symbol, positions)
                } else {
                    highlightVariableDeclaration(identifier, symbol, positions)
                }
                super.visitProperty(property)
            }

            override fun visitParameter(parameter: KtParameter) {
                val identifier = parameter.nameIdentifier ?: return super.visitParameter(parameter)
                // Function type parameters (e.g. in `(Int) -> String`) have no symbol in K2.
                // Their PSI parent chain is KtParameter → KtParameterList → KtFunctionType.
                if (parameter.parent?.parent is KtFunctionType) return super.visitParameter(parameter)
                val symbol = parameter.symbol
                // Constructor parameters with val/var become properties
                if (symbol is KaPropertySymbol) {
                    highlightPropertySymbol(identifier, symbol, positions)
                } else if (symbol is KaValueParameterSymbol) {
                    positions.highlight(KotlinHighlightingAttributes.PARAMETER_VARIABLE, identifier.textRange)
                }
                super.visitParameter(parameter)
            }

            override fun visitNamedFunction(function: KtNamedFunction) {
                val identifier = function.nameIdentifier
                if (identifier != null) {
                    positions.highlight(KotlinHighlightingAttributes.FUNCTION_DECLARATION, identifier.textRange)
                }
                super.visitNamedFunction(function)
            }
        })
    }

    /**
     * Resolves the symbol referenced by [expression] and adds the appropriate highlight.
     *
     * Constructor references are unwrapped to their containing class so that `MyClass()`
     * at the call site is coloured as a class rather than a constructor.
     *
     * @param expression the simple name expression to highlight
     * @param positions  accumulator for highlight ranges
     */
    context(KaSession)
    private fun highlightSimpleName(
        expression: KtSimpleNameExpression,
        positions: HashMap<OffsetRange, Set<ColoringAttributes>>,
    ) {
        val parent = expression.parent
        if (parent is KtThisExpression || parent is KtSuperExpression) return

        val ktRef = expression.references.filterIsInstance<KtReference>().firstOrNull() ?: return
        val rawSymbol = ktRef.resolveToSymbol() ?: return
        val target = (rawSymbol as? KaConstructorSymbol)?.containingSymbol ?: rawSymbol

        when (target) {
            is KaTypeParameterSymbol ->
                positions.highlight(KotlinHighlightingAttributes.TYPE_PARAMETER, expression.textRange)
            is KaEnumEntrySymbol ->
                positions.highlight(KotlinHighlightingAttributes.STATIC_FINAL_FIELD, expression.textRange)
            is KaClassSymbol ->
                highlightClassSymbol(expression, target, positions)
            is KaPropertySymbol ->
                highlightPropertySymbol(expression, target, positions)
            is KaLocalVariableSymbol ->
                positions.highlight(
                    if (target.isVal) KotlinHighlightingAttributes.LOCAL_FINAL_VARIABLE
                    else KotlinHighlightingAttributes.LOCAL_VARIABLE,
                    expression.textRange,
                )
            is KaValueParameterSymbol ->
                positions.highlight(KotlinHighlightingAttributes.PARAMETER_VARIABLE, expression.textRange)
            else -> {}
        }
    }

    /**
     * Adds a highlight range for a class or object symbol, choosing the attribute based on
     * [KaClassSymbol.classKind] (interface, annotation class, enum, or plain class/object).
     *
     * Annotation usages extend the range leftward to include the `@` sign.
     *
     * @param element the PSI element to highlight (identifier or reference expression)
     * @param symbol  the resolved class symbol
     * @param positions accumulator for highlight ranges
     */
    context(KaSession)
    private fun highlightClassSymbol(
        element: PsiElement,
        symbol: KaClassSymbol,
        positions: HashMap<OffsetRange, Set<ColoringAttributes>>,
    ) {
        when (symbol.classKind) {
            KaClassKind.INTERFACE ->
                positions.highlight(KotlinHighlightingAttributes.INTERFACE, element.textRange)
            KaClassKind.ANNOTATION_CLASS -> {
                var range = element.textRange
                val entry = PsiTreeUtil.getParentOfType(
                    element, KtAnnotationEntry::class.java, false, KtValueArgumentList::class.java
                )
                if (entry?.atSymbol != null) {
                    range = TextRange(entry.atSymbol!!.textRange.startOffset, element.textRange.endOffset)
                }
                positions.highlight(KotlinHighlightingAttributes.ANNOTATION, range)
            }
            KaClassKind.ENUM_CLASS, KaClassKind.CLASS,
            KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT, KaClassKind.ANONYMOUS_OBJECT ->
                positions.highlight(KotlinHighlightingAttributes.CLASS, element.textRange)
        }
    }

    /**
     * Adds a highlight range for a property symbol, distinguishing static (top-level or companion)
     * from instance and mutable from immutable.
     *
     * A property is considered "static" when it has no containing class, i.e. it is top-level, or
     * when its containing class is a companion object.
     *
     * @param element the PSI element to highlight
     * @param symbol  the resolved property symbol
     * @param positions accumulator for highlight ranges
     */
    context(KaSession)
    private fun highlightPropertySymbol(
        element: PsiElement,
        symbol: KaPropertySymbol,
        positions: HashMap<OffsetRange, Set<ColoringAttributes>>,
    ) {
        val containingClass = symbol.containingSymbol as? KaClassSymbol
        val isStatic = containingClass == null ||
            containingClass.classKind == KaClassKind.COMPANION_OBJECT
        val attr = when {
            isStatic && !symbol.isVal -> KotlinHighlightingAttributes.STATIC_FIELD
            isStatic && symbol.isVal  -> KotlinHighlightingAttributes.STATIC_FINAL_FIELD
            !symbol.isVal             -> KotlinHighlightingAttributes.FIELD
            else                      -> KotlinHighlightingAttributes.FINAL_FIELD
        }
        positions.highlight(attr, element.textRange)
    }

    /**
     * Adds a highlight range for a variable declaration that is not a [KaPropertySymbol]
     * (e.g. a local variable declared via `var`/`val`).
     *
     * @param element the name identifier of the declaration
     * @param symbol  the resolved variable symbol
     * @param positions accumulator for highlight ranges
     */
    context(KaSession)
    private fun highlightVariableDeclaration(
        element: PsiElement,
        symbol: KaVariableSymbol,
        positions: HashMap<OffsetRange, Set<ColoringAttributes>>,
    ) {
        val attr = if (symbol.isVal) KotlinHighlightingAttributes.LOCAL_FINAL_VARIABLE
                   else KotlinHighlightingAttributes.LOCAL_VARIABLE
        positions.highlight(attr, element.textRange)
    }

    /**
     * Collects K2 diagnostics for [kaKtFile] and marks deprecated elements with the
     * [KotlinHighlightingAttributes.DEPRECATED] style.
     *
     * Uses [KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS] to avoid false positives from
     * platform-specific checkers that may not apply in a standalone analysis session.
     *
     * @param positions accumulator for highlight ranges
     */
    context(KaSession)
    private fun highlightDeprecated(positions: HashMap<OffsetRange, Set<ColoringAttributes>>) {
        kaKtFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
            .filter { it.factoryName.contains("DEPRECATION", ignoreCase = true) }
            .forEach { diag ->
                diag.textRanges.forEach { range ->
                    positions.highlight(KotlinHighlightingAttributes.DEPRECATED, range)
                }
            }
    }

    // ---- helpers -------------------------------------------------------

    private fun HashMap<OffsetRange, Set<ColoringAttributes>>.highlight(
        attr: KotlinHighlightingAttributes,
        range: TextRange,
    ) {
        put(OffsetRange(range.startOffset, range.endOffset), attr.styleKey)
    }

    private fun HashMap<OffsetRange, Set<ColoringAttributes>>.highlight(
        attrs: Set<ColoringAttributes>,
        range: TextRange,
    ) {
        put(OffsetRange(range.startOffset, range.endOffset), attrs)
    }
}
