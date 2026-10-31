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
package com.siyeh.ig.psiutils

import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiField
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.controlFlow.ControlFlowUtil
import com.intellij.psi.controlFlow.LocalsOrMyInstanceFieldsControlFlowPolicy
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import java.util.stream.Stream

/**
 * Standalone replacements for the `com.siyeh.ig.psiutils` helpers used by the J2K engine.
 *
 * The originals live in `java-analysis-impl`, which this plugin does not bundle. Each replacement
 * keeps the original contract for the operations the converter performs (PSI scans, control-flow
 * finality checks) and degrades only where the missing Java index would be required — degradation
 * always errs on the side of producing a valid Kotlin `var` rather than an invalid `val`.
 */

/**
 * Standalone replacement for IDEA's `ExpectedTypeUtils`.
 *
 * Expected-type computation needs the Java type-inference engine, which is not bundled. Returning
 * `null` means "no expected type known"; the converter then keeps the declared type.
 */
object ExpectedTypeUtils {

    /**
     * Returns the type expected at the position of [expression].
     *
     * @param expression the expression whose expected type is requested.
     * @param calculateTypeForComplexReferences ignored — see the class documentation.
     * @return always `null`.
     */
    @JvmStatic
    fun findExpectedType(expression: PsiExpression, calculateTypeForComplexReferences: Boolean): PsiType? =
        null
}

/**
 * Standalone replacement for IDEA's `ExpressionUtils`.
 *
 * Only [nonStructuralChildren] is provided. It expands the structural wrappers the nullability
 * inference needs to see through: conditionals, parentheses, non-primitive casts and the default
 * value of `Map.getOrDefault`.
 */
object ExpressionUtils {

    /**
     * Returns the expressions that determine the value of [expression], skipping structural
     * wrappers (conditionals, parentheses, non-primitive casts).
     *
     * @param expression the expression to decompose.
     * @return the contained value expressions in source order.
     */
    @JvmStatic
    fun nonStructuralChildren(expression: PsiExpression): Stream<PsiExpression> {
        val result = ArrayList<PsiExpression>()
        collectNonStructuralChildren(expression, result)
        return result.stream()
    }

    /**
     * Recursively adds the value expressions of [expression] to [result].
     */
    private fun collectNonStructuralChildren(expression: PsiExpression, result: MutableList<PsiExpression>) {
        when (expression) {
            is PsiConditionalExpression -> {
                expression.thenExpression?.let { collectNonStructuralChildren(it, result) }
                expression.elseExpression?.let { collectNonStructuralChildren(it, result) }
            }

            is PsiParenthesizedExpression -> {
                val inner = expression.expression
                if (inner == null) result.add(expression) else collectNonStructuralChildren(inner, result)
            }

            is PsiTypeCastExpression -> {
                val operand = expression.operand
                val castType = expression.type
                // Ignore to-primitive/from-primitive casts: they may change the value.
                if (operand != null && castType !is PsiPrimitiveType &&
                    (operand.type !is PsiPrimitiveType || PsiTypes.nullType() == operand.type)
                ) {
                    PsiUtil.skipParenthesizedExprDown(operand)?.let { collectNonStructuralChildren(it, result) }
                } else {
                    addExpressionAndDefaultValue(expression, result)
                }
            }

            else -> addExpressionAndDefaultValue(expression, result)
        }
    }

    /**
     * Adds [expression], plus the default value of a `Map.getOrDefault` call.
     */
    private fun addExpressionAndDefaultValue(expression: PsiExpression, result: MutableList<PsiExpression>) {
        result.add(expression)
        if (expression is PsiMethodCallExpression) {
            val arguments = expression.argumentList.expressions
            val method = expression.resolveMethod()
            if (method != null && method.name == "getOrDefault" && arguments.size == 2) {
                result.add(arguments[1])
            }
        }
    }
}

/**
 * Standalone replacement for IDEA's `MethodCallUtils`.
 *
 * Only parameter lookup by argument (with vararg support) is provided; extension-method receivers
 * do not exist in Java source and are therefore not handled.
 */
object MethodCallUtils {

    /**
     * Returns the method parameter that [argument] is passed to.
     *
     * @param argument an argument expression of a call.
     * @return the matching parameter, the vararg parameter for an extra argument, or `null` when
     * [argument] is not a call argument.
     */
    @JvmStatic
    fun getParameterForArgument(argument: PsiExpression): PsiParameter? {
        val argList = argument.parent as? PsiExpressionList ?: return null
        var parent: PsiElement? = argList.parent
        if (parent is PsiAnonymousClass) parent = parent.parent
        val call = parent as? PsiMethodCallExpression ?: return null
        val arguments = argList.expressions
        val index = arguments.indexOfFirst { it === argument }
        if (index < 0) return null
        val method = call.resolveMethod() ?: return null
        val parameters = method.parameterList.parameters
        if (parameters.isEmpty()) return null
        if (index < parameters.size) return parameters[index]
        val last = parameters[parameters.size - 1]
        return if (last.isVarArgs) last else null
    }
}

/**
 * Standalone replacement for IDEA's `VariableAccessUtils`.
 *
 * The original consults the reference index for non-private fields. This plugin has no Java index,
 * so such fields are reported as assigned (an over-approximation that yields `var`, which is always
 * correct Kotlin); private fields and locals are scanned in their PSI scope.
 */
object VariableAccessUtils {

    /**
     * Returns whether [variable] is assigned anywhere in its declaring scope.
     *
     * @param variable the variable to inspect.
     * @return `true` when an assignment exists or cannot be ruled out without an index.
     */
    @JvmStatic
    fun variableIsAssigned(variable: PsiVariable): Boolean {
        if (variable is PsiField) {
            if (!variable.hasModifierProperty(PsiModifier.PRIVATE)) return true
            val containingClass = PsiUtil.getTopLevelClass(variable) ?: return true
            return variableIsAssigned(variable, containingClass)
        }
        val context = PsiTreeUtil.getParentOfType(
            variable,
            PsiCodeBlock::class.java,
            PsiMethod::class.java,
            PsiLambdaExpression::class.java,
            PsiCatchSection::class.java,
            PsiForStatement::class.java,
            PsiForeachStatement::class.java
        ) ?: return true
        return variableIsAssigned(variable, context)
    }

    /**
     * Returns whether [variable] is assigned inside [context].
     *
     * @param variable the variable to inspect.
     * @param context the PSI scope to scan.
     * @return `true` when a writing reference to [variable] exists in [context].
     */
    @JvmStatic
    fun variableIsAssigned(variable: PsiVariable, context: PsiElement?): Boolean {
        if (context == null) return false
        for (reference in PsiTreeUtil.findChildrenOfType(context, PsiReferenceExpression::class.java)) {
            if (reference.isReferenceTo(variable) && PsiUtil.isAccessedForWriting(reference)) {
                return true
            }
        }
        return false
    }

    /**
     * Collects the references to [variable] inside [context].
     *
     * @param variable the variable to look for.
     * @param context the PSI scope to scan; the declaring file is used when `null`.
     * @return the matching references.
     */
    @JvmStatic
    fun getVariableReferences(variable: PsiVariable, context: PsiElement?): List<PsiReferenceExpression> {
        val scope = context ?: variable.containingFile ?: return emptyList()
        val result = ArrayList<PsiReferenceExpression>()
        for (reference in PsiTreeUtil.findChildrenOfType(scope, PsiReferenceExpression::class.java)) {
            if (reference.isReferenceTo(variable)) result.add(reference)
        }
        return result
    }
}

/**
 * Standalone replacement for IDEA's `FinalUtils`.
 *
 * [checkElementDoesNotViolateFinality] is a faithful port of the IDEA implementation; it relies on
 * the Java control-flow utilities that are available in the bundled era-193 platform. Only the
 * inspection-facing helpers of the original class (`canBeFinal`, etc.) are omitted because the J2K
 * engine does not use them.
 */
object FinalUtils {

    /**
     * Checks whether [e] is compatible with [variable] being effectively final.
     *
     * @param e the element to check.
     * @param variable the variable being tested for finality.
     * @param uninitializedVarProblems cache of places where variables may be used before
     * initialization.
     * @param finalVarProblems cache of places where variables are already initialized.
     * @return `true` when [e] does not prevent [variable] from being final.
     */
    @JvmStatic
    fun checkElementDoesNotViolateFinality(
        e: PsiElement,
        variable: PsiVariable,
        uninitializedVarProblems: Map<PsiElement, Collection<PsiReferenceExpression>>,
        finalVarProblems: Map<PsiElement, Collection<ControlFlowUtil.VariableInfo>>
    ): Boolean {
        if (e !is PsiReferenceExpression) return true
        if (!e.isReferenceTo(variable)) return true
        if (!ControlFlowUtil.isInitializedBeforeUsage(e, variable, uninitializedVarProblems, true)) {
            return false
        }
        if (!PsiUtil.isAccessedForWriting(e)) return true
        if (!LocalsOrMyInstanceFieldsControlFlowPolicy.isLocalOrMyInstanceReference(e)) return false
        if (ControlFlowUtil.isVariableAssignedInLoop(e, variable)) return false
        if (variable is PsiField) {
            if (PsiUtil.findEnclosingConstructorOrInitializer(e) == null) return false
            val innerScope = ControlFlowUtil.getScopeEnforcingEffectiveFinality(variable, e)
            if (innerScope != null && innerScope != variable.containingClass) return false
        }
        return ControlFlowUtil.findFinalVariableAlreadyInitializedProblem(variable, e, finalVarProblems) ==
                ControlFlowUtil.DoubleInitializationProblem.NO_PROBLEM
    }
}
