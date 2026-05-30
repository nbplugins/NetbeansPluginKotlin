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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import io.github.nbplugins.kotlin.nbm.hints.atomicChange
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.idea.codeinsight.utils.ImplicitReceiverInfo
import org.jetbrains.kotlin.idea.codeinsight.utils.getImplicitReceiverInfo
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelector

private val FOR_EACH_NAME: Name = Name.identifier("forEach")
private val FOR_EACH_INDEXED_NAME: Name = Name.identifier("forEachIndexed")

private val FOR_EACH_CALLABLE_IDS: Set<CallableId> = setOf(
    CallableId(StandardClassIds.BASE_COLLECTIONS_PACKAGE, FOR_EACH_NAME),
    CallableId(StandardClassIds.BASE_KOTLIN_PACKAGE.child(Name.identifier("sequences")), FOR_EACH_NAME),
    CallableId(StandardClassIds.BASE_KOTLIN_PACKAGE.child(Name.identifier("text")), FOR_EACH_NAME),
)
private val FOR_EACH_INDEXED_CALLABLE_IDS: Set<CallableId> = setOf(
    CallableId(StandardClassIds.BASE_COLLECTIONS_PACKAGE, FOR_EACH_INDEXED_NAME),
    CallableId(StandardClassIds.BASE_KOTLIN_PACKAGE.child(Name.identifier("sequences")), FOR_EACH_INDEXED_NAME),
    CallableId(StandardClassIds.BASE_KOTLIN_PACKAGE.child(Name.identifier("text")), FOR_EACH_INDEXED_NAME),
)

/**
 * Converts a `forEach` / `forEachIndexed` call to an equivalent `for` loop.
 *
 * For example:
 * ```kotlin
 * list.forEach { item ->
 *     process(item)
 * }
 * ```
 * becomes:
 * ```kotlin
 * for (item in list) {
 *     process(item)
 * }
 * ```
 *
 * `return` expressions inside the lambda that target the lambda itself are converted to
 * `continue` in the resulting loop body.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertForEachToForLoopIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    /** Finds the enclosing `forEach` / `forEachIndexed` call expression. */
    private fun findCallExpr(): KtCallExpression? =
        PsiTreeUtil.getParentOfType(psi, KtCallExpression::class.java, false)
            ?.takeIf { it.isForEachPsi() }

    override fun isApplicable(caretOffset: Int): Boolean {
        val callExpr = findCallExpr() ?: return false
        return analyze(kaKtFile) {
            val sym = callExpr.calleeExpression?.mainReference?.resolveToSymbol()
                as? KaNamedFunctionSymbol ?: return@analyze false
            val id = sym.callableId
            if (id !in FOR_EACH_CALLABLE_IDS && id !in FOR_EACH_INDEXED_CALLABLE_IDS) return@analyze false
            if (callExpr.isUsedAsExpression) return@analyze false
            val receiver = callExpr.getQualifiedExpressionForSelector()?.receiverExpression
            if (receiver == null) {
                callExpr.getImplicitReceiverInfo() != null
            } else {
                true
            }
        }
    }

    override fun getDescription(): String = "Replace 'forEach' with a 'for' loop"

    override fun implement() {
        val callExpr = findCallExpr() ?: return
        val lambda = callExpr.singleLambdaArg() ?: return
        val body = lambda.bodyExpression ?: return
        val isIndexed = callExpr.callNameText() == FOR_EACH_INDEXED_NAME.asString()

        data class ReceiverContext(
            val receiverText: String?,
            val implicitInfo: ImplicitReceiverInfo?
        )

        val ctx = analyze(kaKtFile) {
            val qualified = callExpr.getQualifiedExpressionForSelector()
            val recv = qualified?.receiverExpression
            val implicit: ImplicitReceiverInfo? = if (recv == null) callExpr.getImplicitReceiverInfo() else null
            ReceiverContext(recv?.text, implicit)
        }

        val loopRangeText = when {
            ctx.receiverText != null -> ctx.receiverText
            ctx.implicitInfo != null -> {
                if (ctx.implicitInfo.isUnambiguousLabel) "this"
                else "this@${ctx.implicitInfo.receiverLabel?.identifier ?: return}"
            }
            else -> return
        }

        // Replace return@forEach or return@forEachIndexed → continue
        val rawBodyText = body.text.trim()
        val bodyText = rawBodyText
            .replace(Regex("\\breturn@forEach\\b")) { "continue" }
            .replace(Regex("\\breturn@forEachIndexed\\b")) { "continue" }

        val loopText = if (isIndexed) {
            val params = lambda.functionLiteral.valueParameters
            val p0 = params.getOrNull(0)?.text ?: "index"
            val p1 = params.getOrNull(1)?.text ?: "element"
            val receiver = callExpr.getQualifiedExpressionForSelector()?.receiverExpression
            val rangeExpr = if (receiver is KtThisExpression && receiver.labelQualifier == null) {
                "withIndex()"
            } else {
                "$loopRangeText.withIndex()"
            }
            "for (($p0, $p1) in $rangeExpr) {\n$bodyText\n}"
        } else {
            val loopVar = lambda.functionLiteral.valueParameters.singleOrNull()?.text
                ?: StandardNames.IMPLICIT_LAMBDA_PARAMETER_NAME.identifier
            "for ($loopVar in $loopRangeText) {\n$bodyText\n}"
        }

        val targetExpr = callExpr.getQualifiedExpressionForSelector() ?: callExpr
        val start = targetExpr.textRange.startOffset
        doc.atomicChange {
            remove(start, targetExpr.textRange.length)
            insertString(start, loopText, null)
        }
    }

    // ── PSI helpers ────────────────────────────────────────────────────────

    private fun KtCallExpression.callNameText(): String? = calleeExpression?.text

    private fun KtCallExpression.isForEachPsi(): Boolean {
        val name = callNameText()
        val isForEach = name == FOR_EACH_NAME.asString()
        val isForEachIndexed = name == FOR_EACH_INDEXED_NAME.asString()
        if (!isForEach && !isForEachIndexed) return false

        val qualified = getQualifiedExpressionForSelector()
        if (qualified is KtSafeQualifiedExpression &&
            qualified.receiverExpression !is KtNameReferenceExpression
        ) return false

        val lambdaArg = singleLambdaArg() ?: return false
        val paramSize = lambdaArg.functionLiteral.valueParameters.size
        if (isForEach && paramSize > 1) return false
        if (isForEachIndexed && paramSize != 2) return false
        return lambdaArg.bodyExpression != null
    }

    private fun KtCallExpression.singleLambdaArg(): KtLambdaExpression? =
        valueArguments.singleOrNull()?.getArgumentExpression() as? KtLambdaExpression
}
