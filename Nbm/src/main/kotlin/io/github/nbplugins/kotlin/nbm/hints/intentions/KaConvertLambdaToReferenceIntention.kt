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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.singleVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.idea.codeinsight.utils.ConvertLambdaToReferenceUtils.getCallReferencedName
import org.jetbrains.kotlin.idea.codeinsight.utils.ConvertLambdaToReferenceUtils.getSafeReferencedName
import org.jetbrains.kotlin.idea.codeinsight.utils.ConvertLambdaToReferenceUtils.singleStatementOrNull
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Converts a single-statement lambda to a callable reference.
 *
 * For example:
 * ```kotlin
 * list.map { item -> item.toString() }
 * ```
 * becomes:
 * ```kotlin
 * list.map(Any::toString)
 * ```
 *
 * Applicable when the lambda body contains exactly one statement that is a simple call or
 * dot-qualified call, and the lambda is not a trailing argument. Trailing-lambda argument
 * cases require rewriting the entire outer call and are not handled here.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertLambdaToReferenceIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    /** Finds the enclosing lambda expression. */
    private fun findLambdaExpr(): KtLambdaExpression? =
        PsiTreeUtil.getParentOfType(psi, KtLambdaExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val lambda = findLambdaExpr() ?: return false

        val stmt = lambda.singleStatementOrNull() ?: return false
        val isCallLike = when (stmt) {
            is KtCallExpression -> calleeRef(stmt) != null
            is KtDotQualifiedExpression -> {
                if (stmt.receiverExpression is KtSuperExpression) return false
                val sel = stmt.selectorExpression ?: return false
                calleeRef(sel) != null
            }
            else -> false
        }
        if (!isCallLike) return false

        // Skip trailing lambda (rewriting the call expression is out of scope here)
        if (lambda.parentValueArg() is KtLambdaArgument) return false

        return analyze(kaKtFile) { buildReferenceTextFor(lambda) != null }
    }

    override fun getDescription(): String = "Convert lambda to reference"

    override fun implement() {
        val lambda = findLambdaExpr() ?: return
        if (lambda.parentValueArg() is KtLambdaArgument) return

        val refText = analyze(kaKtFile) { buildReferenceTextFor(lambda) } ?: return
        val start = lambda.textRange.startOffset
        doc.atomicChange {
            remove(start, lambda.textRange.length)
            insertString(start, refText, null)
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun calleeRef(expr: KtExpression): KtNameReferenceExpression? = when (expr) {
        is KtCallExpression -> expr.calleeExpression as? KtNameReferenceExpression
        is KtNameReferenceExpression -> expr
        else -> null
    }

    private fun KtLambdaExpression.parentValueArg(): KtValueArgument? =
        (if (parent is KtLabeledExpression) parent.parent else parent) as? KtValueArgument

    private fun org.jetbrains.kotlin.analysis.api.KaSession.lambdaParamType(
        lambda: KtLambdaExpression
    ) = lambda.parentValueArg()?.let { arg ->
        arg.getStrictParentOfType<KtCallExpression>()
            ?.resolveToCall()?.successfulFunctionCallOrNull()
            ?.argumentMapping?.get(arg.getArgumentExpression())?.returnType
    }

    @OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
    private fun org.jetbrains.kotlin.analysis.api.KaSession.buildReferenceTextFor(
        lambda: KtLambdaExpression
    ): String? {
        val paramType = lambdaParamType(lambda)
        val isExtension = (paramType as? KaFunctionType)?.hasReceiver == true

        return when (val stmt = lambda.singleStatementOrNull()) {
            is KtCallExpression -> {
                val ref = stmt.calleeExpression as? KtNameReferenceExpression ?: return null
                val call = ref.resolveToCall()?.successfulFunctionCallOrNull() ?: return null
                val sym = call.symbol
                val lambdaParams = lambda.functionLiteral.symbol.valueParameters
                if (sym.valueParameters.size != stmt.valueArguments.size) return null
                if (lambdaParams.size != stmt.valueArguments.size) return null
                val receiverText = if (isExtension) renderTarget(ref) else ""
                val selectorText = stmt.getCallReferencedName() ?: return null
                val isInvoke = sym is KaNamedFunctionSymbol && sym.isOperator &&
                        sym.name == OperatorNameConventions.INVOKE
                makeRef(receiverText, selectorText, isInvoke)
            }

            is KtDotQualifiedExpression -> {
                val (selectorRef, selectorName) = when (val sel = stmt.selectorExpression) {
                    is KtCallExpression -> {
                        val callee = sel.calleeExpression as? KtNameReferenceExpression ?: return null
                        callee to callee.getSafeReferencedName()
                    }
                    is KtNameReferenceExpression -> sel to sel.getSafeReferencedName()
                    else -> return null
                }
                val receiver = stmt.receiverExpression
                val call = stmt.selectorExpression?.resolveToCall()?.successfulFunctionCallOrNull()
                val callSym = call?.symbol
                val isInvoke = callSym is KaNamedFunctionSymbol && callSym.isOperator &&
                        callSym.name == OperatorNameConventions.INVOKE
                when (receiver) {
                    is KtNameReferenceExpression -> {
                        val recvCall = receiver.resolveToCall()?.singleVariableAccessCall() ?: return null
                        val recvSym = recvCall.symbol
                        val lambdaParams = lambda.functionLiteral.symbol.valueParameters
                        if (recvSym is KaValueParameterSymbol &&
                            recvSym == lambdaParams.firstOrNull()
                        ) {
                            makeRef(recvSym.returnType.render(position = Variance.IN_VARIANCE), selectorName, isInvoke)
                        } else {
                            val recvName = (recvSym as? KaNamedSymbol)?.name?.asString() ?: return null
                            makeRef(recvName, selectorName, isInvoke)
                        }
                    }
                    else -> {
                        val receiverText = if (isExtension) renderTarget(selectorRef) else receiver.text
                        makeRef(receiverText, selectorName, isInvoke)
                    }
                }
            }

            else -> null
        }
    }

    private fun makeRef(receiver: String, selector: String, isInvoke: Boolean): String {
        val invokeRef = if (isInvoke) "::invoke" else ""
        return if (receiver.isEmpty()) "::$selector$invokeRef" else "$receiver::$selector$invokeRef"
    }

    @OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
    private fun org.jetbrains.kotlin.analysis.api.KaSession.renderTarget(
        expr: KtNameReferenceExpression
    ): String {
        val pas = expr.resolveToCall()
            ?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol
        val receiverType = (pas?.dispatchReceiver ?: pas?.extensionReceiver)?.type ?: return ""
        return receiverType.render(position = Variance.IN_VARIANCE)
    }
}
