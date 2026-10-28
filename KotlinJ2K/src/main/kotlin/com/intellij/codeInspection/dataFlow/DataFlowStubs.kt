/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
package com.intellij.codeInspection.dataFlow

import com.intellij.codeInsight.Nullability
import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInspection.dataFlow.types.DfType
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

/**
 * Standalone replacements for the IntelliJ **data-flow analysis** classes referenced by the J2K
 * engine (`org.jetbrains.kotlin.nj2k.nullabilityUtils`, `J2KNullityInferrer`,
 * `NullabilityConversion`).
 *
 * The Java DFA lives in the `java-analysis-impl` artifact, which this plugin does not bundle: the
 * standalone analysis session has neither IntelliJ's Java index nor its DFA bytecode. The classes
 * below therefore reproduce only the **annotation-based** part of nullability inference (the part
 * that needs nothing but PSI); every DFA-specific query degrades to `UNKNOWN`.
 *
 * Consequence for the conversion result: types are annotated `?`/non-`?` based on declared
 * annotations and obvious syntactic facts (literals, `new`, primitives), not on data flow. Kotlin
 * output stays compilable because `UNKNOWN` keeps the source's original nullability.
 */

/**
 * Standalone replacement for IDEA's `DfaNullability` enum.
 *
 * Only [NOT_NULL], [NULLABLE] and [UNKNOWN] can be produced by the stand-ins in this package;
 * [NULL] and [FLUSHED] exist so that copied engine code referencing them still compiles.
 */
enum class DfaNullability {
    /** The value is definitely `null`. */
    NULL,

    /** The value may be `null`. */
    NULLABLE,

    /** The value is never `null`. */
    NOT_NULL,

    /** The analysis could not determine the nullability. */
    UNKNOWN,

    /** The cached data-flow value was invalidated. */
    FLUSHED
}

/**
 * Standalone replacement for IDEA's `DataflowResult`: the result of running the Java DFA on an
 * expression.
 *
 * This plugin does not run the DFA, so [getDfType] always returns `null`; callers treat that as
 * "unknown".
 */
abstract class DataflowResult {

    /**
     * Returns the inferred type of [expression].
     *
     * @param expression the expression to query.
     * @return always `null` — no data flow is computed in standalone mode.
     */
    abstract fun getDfType(expression: PsiExpression): DfType?
}

/**
 * Standalone replacement for IDEA's `CommonDataflow`.
 *
 * Running the Java DFA requires the `java-analysis-impl` artifact and a fully built Java index;
 * neither exists in the standalone NetBeans session, so every query reports "no data flow".
 */
object CommonDataflow {

    /**
     * Computes (or returns a cached) data-flow result for [expression].
     *
     * @param expression the expression to analyse.
     * @return always `null` — see the class documentation.
     */
    @JvmStatic
    fun getDataflowResult(expression: PsiExpression): DataflowResult? = null
}

/**
 * Standalone replacement for IDEA's `DfaUtil`.
 *
 * Only the annotation-based part of `inferMethodNullability` is implemented: a primitive return
 * type is never null, and otherwise the method's `@Nullable`/`@NotNull` annotations decide.
 */
object DfaUtil {

    /**
     * Infers the nullability of [method]'s return value.
     *
     * @param method the method to inspect.
     * @return [Nullability.NOT_NULL] for primitive return types, the annotated nullability when the
     * method carries a nullability annotation, otherwise [Nullability.UNKNOWN].
     */
    @JvmStatic
    fun inferMethodNullability(method: PsiMethod): Nullability {
        if (method.returnType is PsiPrimitiveType) return Nullability.NOT_NULL
        return NullableNotNullManager.getNullability(method)
    }
}

/**
 * Standalone replacement for IDEA's `DfaPsiUtil`.
 *
 * Assignment collection works over the declaring file only (no index-backed search); the
 * data-flow-based helpers return neutral values.
 */
object DfaPsiUtil {

    /**
     * Computes the nullability a *declaration* of [type] should have when written as a Kotlin type.
     *
     * @param type the declared type.
     * @param owner the element that declares the type (may be `null`, e.g. for a standalone type
     * element).
     * @return [Nullability.NOT_NULL] for primitive types, the annotated nullability when [owner]
     * carries a nullability annotation, otherwise [Nullability.UNKNOWN].
     */
    @JvmStatic
    fun getElementNullabilityForWrite(type: PsiType?, owner: PsiModifierListOwner?): Nullability {
        if (type is PsiPrimitiveType) return Nullability.NOT_NULL
        if (owner == null) return Nullability.UNKNOWN
        return NullableNotNullManager.getNullability(owner)
    }

    /**
     * Collects the expressions assigned to [variable] in its declaring file.
     *
     * @param variable the variable to look for.
     * @param acceptNonWriteAccess whether references that only read the variable should also be
     * collected.
     * @param place the file (or an element inside it) to search; when `null` the variable's own
     * file is used.
     * @return the right-hand sides of assignments to [variable] (and, when requested, its read
     * references). Empty when the variable has no containing file.
     */
    @JvmStatic
    fun getVariableAssignmentsInFile(
        variable: PsiVariable,
        acceptNonWriteAccess: Boolean,
        place: PsiElement?
    ): Collection<PsiExpression> {
        val file = place?.containingFile ?: variable.containingFile ?: return emptyList()
        val result = ArrayList<PsiExpression>()
        for (assignment in PsiTreeUtil.findChildrenOfType(file, PsiAssignmentExpression::class.java)) {
            if (assignment.operationTokenType != JavaTokenType.EQ) continue
            val left = assignment.lExpression as? PsiReferenceExpression ?: continue
            if (!left.isReferenceTo(variable)) continue
            assignment.rExpression?.let { result.add(it) }
        }
        if (acceptNonWriteAccess) {
            for (reference in PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression::class.java)) {
                if (reference.isReferenceTo(variable)) result.add(reference)
            }
        }
        return result
    }

    /**
     * Returns whether the expression is an assertion that has an effect on nullability.
     *
     * No data flow is computed in standalone mode, so assertions are never treated as effective.
     *
     * @param expression the expression to inspect.
     * @param negated whether the assertion is negated (`!= null` style).
     * @return always `false`.
     */
    @JvmStatic
    fun isAssertionEffectively(expression: PsiExpression, negated: Boolean): Boolean = false
}

/**
 * Standalone replacement for IDEA's `NullabilityUtil`.
 *
 * Implements the syntactic cases of `getExpressionNullability` (literals, `new`, primitives,
 * conditionals, casts, assignments and calls to annotated methods). The `useDataflow` flag is
 * accepted for source compatibility and ignored, because this plugin has no Java DFA.
 */
object NullabilityUtil {

    /**
     * Returns the nullability of [expression] without data flow.
     *
     * @param expression the expression to inspect (may be `null`).
     * @return the syntactic nullability, or [Nullability.UNKNOWN].
     */
    @JvmStatic
    fun getExpressionNullability(expression: PsiExpression?): Nullability =
        getExpressionNullability(expression, false)

    /**
     * Returns the nullability of [expression].
     *
     * @param expression the expression to inspect (may be `null`).
     * @param useDataflow ignored — data flow is not available in standalone mode.
     * @return [Nullability.NULLABLE] for `null`, [Nullability.NOT_NULL] for `new`, literals,
     * polyadic and functional expressions and primitives, the annotated nullability for calls and
     * references, otherwise [Nullability.UNKNOWN].
     */
    @JvmStatic
    fun getExpressionNullability(expression: PsiExpression?, useDataflow: Boolean): Nullability {
        val expr = PsiUtil.skipParenthesizedExprDown(expression) ?: return Nullability.UNKNOWN
        if (PsiTypes.nullType() == expr.type) return Nullability.NULLABLE
        if (expr is PsiNewExpression
            || expr is PsiLiteralExpression
            || expr is PsiPolyadicExpression
            || expr.type is PsiPrimitiveType
        ) {
            return Nullability.NOT_NULL
        }
        if (expr is PsiConditionalExpression) {
            val thenExpression = expr.thenExpression
            val elseExpression = expr.elseExpression
            if (thenExpression == null || elseExpression == null) return Nullability.UNKNOWN
            val left = getExpressionNullability(thenExpression, false)
            if (left == Nullability.UNKNOWN) return Nullability.UNKNOWN
            val right = getExpressionNullability(elseExpression, false)
            return if (left == right) left else Nullability.UNKNOWN
        }
        if (expr is PsiTypeCastExpression) {
            return getExpressionNullability(expr.operand, useDataflow)
        }
        if (expr is PsiAssignmentExpression) {
            return if (expr.operationTokenType == JavaTokenType.EQ) {
                getExpressionNullability(expr.rExpression, useDataflow)
            } else {
                Nullability.NOT_NULL
            }
        }
        if (expr is PsiMethodCallExpression) {
            val method = expr.resolveMethod() ?: return Nullability.UNKNOWN
            return DfaUtil.inferMethodNullability(method)
        }
        if (expr is PsiReferenceExpression) {
            val target = expr.resolve()
            if (target is PsiModifierListOwner) {
                val annotated = NullableNotNullManager.getNullability(target)
                if (annotated != Nullability.UNKNOWN) return annotated
            }
            return Nullability.UNKNOWN
        }
        return Nullability.UNKNOWN
    }
}
