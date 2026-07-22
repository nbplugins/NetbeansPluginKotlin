// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.components.declaredMemberScope
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.scopes.KaScope
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.signatures.KaFunctionSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
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

/**
 * Returns a "this@X" qualifier string for an implicit receiver, for use during code inlining.
 * Simplified port from IntelliJ's utils.kt (avoids contextReceivers property removed in era 253).
 */
context(_: KaSession)
@OptIn(KaExperimentalApi::class)
fun getThisQualifier(receiverValue: KaImplicitReceiverValue): String {
    val symbol = receiverValue.symbol
    return when {
        symbol is KaClassSymbol && symbol.classKind == KaClassKind.COMPANION_OBJECT ->
            symbol.containingSymbol?.let { container ->
                if (container is KaClassifierSymbol)
                    "${container.name!!.asString()}.${symbol.name!!.asString()}"
                else "this"
            } ?: "this"
        symbol is KaClassSymbol && symbol.classKind == KaClassKind.OBJECT ->
            symbol.name!!.asString()
        symbol is KaClassifierSymbol && symbol !is KaAnonymousObjectSymbol ->
            (symbol.psi as? PsiClass)?.name ?: ("this@" + symbol.name!!.asString())
        symbol is KaReceiverParameterSymbol ->
            (symbol.owningCallableSymbol as? KaNamedSymbol)?.name?.let { "this@${it.asString()}" } ?: "this"
        else -> "this"
    }
}

/**
 * Finds a callable member of [container] by its K2 signature.
 *
 * Ported verbatim in behavior from IDEA's 2025.3 `utils.kt`; Extract Super uses it to detect
 * existing declarations in the newly created interface or superclass before moving a member.
 *
 * @param container class-like declaration container to search.
 * @param callableSignature callable signature to match.
 * @param ignoreReturnType whether return type differences should be ignored.
 * @return matching declared callable, or `null`.
 */
@OptIn(KaExperimentalApi::class)
fun KaSession.findCallableMemberBySignature(
    container: KaDeclarationContainerSymbol,
    callableSignature: KaCallableSignature<KaCallableSymbol>,
    ignoreReturnType: Boolean = false,
): KaCallableSymbol? = findCallableMemberBySignature(
    scope = container.declaredMemberScope,
    callableSignature = callableSignature,
    ignoreReturnType = ignoreReturnType,
)

/**
 * Finds a callable member in this scope by its K2 signature.
 *
 * @param scope declared-member scope to search.
 * @param callableSignature callable signature to match.
 * @param ignoreReturnType whether return type differences should be ignored.
 * @return matching callable, or `null`.
 */
@OptIn(KaExperimentalApi::class)
fun KaSession.findCallableMemberBySignature(
    scope: KaScope,
    callableSignature: KaCallableSignature<KaCallableSymbol>,
    ignoreReturnType: Boolean = false,
): KaCallableSymbol? {
    fun KaType?.eq(anotherType: KaType?): Boolean {
        if (this == null || anotherType == null) return this == anotherType
        return this.semanticallyEquals(anotherType)
    }

    val callableName = (callableSignature.symbol as? KaNamedSymbol)?.name ?: return null
    return scope.callables(callableName).firstOrNull { callable ->
        fun parametersMatch(): Boolean {
            if (callableSignature is KaFunctionSignature && callable is KaFunctionSymbol) {
                if (callable.valueParameters.size != callableSignature.valueParameters.size) return false
                return callable.valueParameters.zip(callableSignature.valueParameters)
                    .all { (left, right) -> left.returnType.eq(right.returnType) }
            }
            return callableSignature !is KaFunctionSignature && callable !is KaFunctionSymbol
        }

        parametersMatch() &&
            (ignoreReturnType || callable.returnType.semanticallyEquals(callableSignature.returnType))
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
