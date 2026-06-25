// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.idea.refactoring.getLastLambdaExpression
import org.jetbrains.kotlin.idea.refactoring.isComplexCallWithLambdaArgument
import org.jetbrains.kotlin.idea.refactoring.moveFunctionLiteralOutsideParentheses
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Computes [block] and removes any possible redundant imports that would be added during this operation,
 * not touching any existing redundant imports. Stub: runs [block] directly without import optimization.
 */
fun <T> modifyPsiWithOptimizedImports(@Suppress("UNUSED_PARAMETER") file: KtFile, block: () -> T): T = block()

fun PsiElement?.canDeleteElement(): Boolean {
    if (this is KtObjectDeclaration && isObjectLiteral()) return false

    if (this is KtParameter) {
        val parameterList = parent as? KtParameterList ?: return false
        val declaration = parameterList.parent as? KtDeclaration ?: return false
        return declaration !is KtPropertyAccessor
    }

    return this is KtClassOrObject
            || this is KtSecondaryConstructor
            || this is KtNamedFunction
            || this is KtProperty
            || this is KtTypeParameter
            || this is KtTypeAlias
}

/**
 * Stub: in IDEA shows a dialog asking whether to also affect super-methods.
 * In NetBeans standalone mode, always acts on the declaration itself only.
 */
fun checkSuperMethods(
    declaration: KtDeclaration,
    @Suppress("UNUSED_PARAMETER") ignore: Collection<PsiElement>,
    @Suppress("UNUSED_PARAMETER") @Nls actionString: String,
): List<PsiElement> {
    if (!declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return listOf(declaration)
    return listOf(declaration)
}

fun KtCallExpression.canMoveLambdaOutsideParentheses(skipComplexCalls: Boolean = true): Boolean {
    if (skipComplexCalls && isComplexCallWithLambdaArgument()) {
        return false
    }

    if (getStrictParentOfType<KtDelegatedSuperTypeEntry>() != null) {
        return false
    }
    val lastLambdaExpression = getLastLambdaExpression() ?: return false

    fun KtExpression.parentLabeledExpression(): KtLabeledExpression? {
        return getStrictParentOfType<KtLabeledExpression>()?.takeIf { it.baseExpression == this }
    }

    if (lastLambdaExpression.parentLabeledExpression()?.parentLabeledExpression() != null) {
        return false
    }

    val callee = calleeExpression
    if (callee !is KtNameReferenceExpression) return true

    analyze(callee) {
        val resolveCall = callee.resolveToCall() ?: return false
        val call = resolveCall.successfulFunctionCallOrNull()

        fun KaType.isFunctionalType(): Boolean =
            this is KaTypeParameterType || isSuspendFunctionType || isFunctionType || isFunctionalInterface

        if (call == null) {
            val paramType = resolveCall.successfulVariableAccessCall()?.partiallyAppliedSymbol?.symbol?.returnType
            if (paramType != null && paramType.isFunctionalType()) {
                return true
            }
            val calls = (resolveCall as KaErrorCallInfo).candidateCalls.filterIsInstance<KaSimpleFunctionCall>()

            return calls.isEmpty() || calls.all { functionalCall ->
                val lastParameter = functionalCall.partiallyAppliedSymbol.signature.valueParameters.lastOrNull()
                val lastParameterType = lastParameter?.returnType
                lastParameterType != null && lastParameterType.isFunctionalType()
            }
        }

        val lastParameter = call.argumentMapping[lastLambdaExpression]
            ?: lastLambdaExpression.parentLabeledExpression()?.let(call.argumentMapping::get)
            ?: return false

        if (lastParameter.symbol.isVararg) {
            return false
        }
        if (lastParameter.symbol != call.partiallyAppliedSymbol.signature.valueParameters.lastOrNull()?.symbol) {
            return false
        }

        return lastParameter.returnType.isFunctionalType()
    }
}

fun KtLambdaExpression.moveFunctionLiteralOutsideParenthesesIfPossible() {
    val valueArgument = parentOfType<KtValueArgument>()?.takeIf {
        KtPsiUtil.deparenthesize(it.getArgumentExpression()) == this
    } ?: return
    val valueArgumentList = valueArgument.parent as? KtValueArgumentList ?: return
    val call = valueArgumentList.parent as? KtCallExpression ?: return
    if (call.canMoveLambdaOutsideParentheses()) {
        call.moveFunctionLiteralOutsideParentheses()
    }
}
