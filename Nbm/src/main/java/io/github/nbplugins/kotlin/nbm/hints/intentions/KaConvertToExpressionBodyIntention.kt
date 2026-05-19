@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.text.Document
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtDeclarationWithInitializer
import org.jetbrains.kotlin.hints.atomicChange
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.reformatting.moveCursorTo
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.intentions.ConvertToExpressionBodyIntention].
 *
 * Converts a block-body function with a single return statement to expression-body form.
 * The K2 version removes the [org.jetbrains.kotlin.resolve.BindingContext.EXHAUSTIVE_WHEN]
 * check (handled conservatively: if a `when` has no `else` branch we skip conversion).
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertToExpressionBodyIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    override fun isApplicable(caretOffset: Int): Boolean {
        val element = PsiTreeUtil.getParentOfType(psi, KtDeclarationWithBody::class.java) ?: return false
        if (element is KtConstructor<*>) return false
        if (element is KtFunctionLiteral) return false

        // Don't trigger when psi is inside a nested property/variable declaration within element
        // (e.g. caret on `val a: String = "A"` inside a function should not convert the function).
        val enclosingInit = PsiTreeUtil.getParentOfType(psi, KtDeclarationWithInitializer::class.java)
        if (enclosingInit != null && PsiTreeUtil.isAncestor(element, enclosingInit, true)) return false

        val value = calcValue(element) ?: return false

        return !value.anyDescendantOfType<KtReturnExpression>(
            canGoInside = { it !is KtFunctionLiteral && it !is KtNamedFunction && it !is KtPropertyAccessor }
        )
    }

    override fun getDescription(): String = "Convert to expression body"

    override fun implement() {
        val element = PsiTreeUtil.getParentOfType(psi, KtDeclarationWithBody::class.java) ?: return
        val expression = calcValue(element) ?: return
        val block = blockExpression(element) ?: return

        if (element !is org.jetbrains.kotlin.psi.KtCallableDeclaration) return

        val colon = element.colon?.textRange?.startOffset ?: element.bodyExpression!!.textRange.startOffset

        val type = if (!element.hasDeclaredReturnType()
            && element is KtNamedFunction
            && block.statements.isNotEmpty()
        ) {
            val isUnit = analyze(kaKtFile) { expression.expressionType?.isUnitType ?: false }
            if (isUnit) {
                org.jetbrains.kotlin.builtins.StandardNames.FqNames.unit.shortName().asString()
            } else return
        } else if (element.hasDeclaredReturnType()) {
            element.typeReference!!.text
        } else ""

        val endOffset = element.bodyExpression!!.textRange.endOffset

        doc.atomicChange {
            remove(colon, endOffset - colon)
            insertString(colon, ": $type = ${expression.text}", null)
            moveCursorTo(colon)
        }
    }

    private fun blockExpression(declaration: KtDeclarationWithBody): KtBlockExpression? {
        if (declaration is KtFunctionLiteral) return null
        val body = declaration.bodyExpression
        return if (!declaration.hasBlockBody() || body !is KtBlockExpression) null else body
    }

    private fun calcValue(declaration: KtDeclarationWithBody): KtExpression? {
        val body = blockExpression(declaration) ?: return null
        return calcValue(body)
    }

    private fun calcValue(body: KtBlockExpression): KtExpression? {
        val bodyStatements = body.statements
        if (bodyStatements.isEmpty()) {
            return KtPsiFactory(body).createExpression("Unit")
        }
        val statement = bodyStatements.singleOrNull() ?: return null
        return when (statement) {
            is KtReturnExpression -> statement.returnedExpression
            is KtDeclaration, is KtLoopExpression -> null
            else -> {
                if (statement is KtBinaryExpression && statement.operationToken in KtTokens.ALL_ASSIGNMENTS) return null

                val (isUnit, isNothing) = analyze(kaKtFile) {
                    val exprType = statement.expressionType ?: return@analyze false to false
                    exprType.isUnitType to exprType.isNothingType
                }
                if (!isUnit && !isNothing) return null
                if (isUnit) {
                    if (statement.hasResultingIfWithoutElse()) return null
                    // Conservative: skip when a `when` has no else (without exhaustiveness check)
                    val resultingWhens = statement.resultingWhens()
                    if (resultingWhens.any { it.elseExpression == null }) return null
                }
                statement
            }
        }
    }
}

private fun KtExpression.hasResultingIfWithoutElse(): Boolean =
    anyDescendantOfType<KtIfExpression> { it.`else` == null }

private fun KtExpression.resultingWhens(): List<KtWhenExpression> =
    buildList { collectResultingWhens(this@resultingWhens, this) }

private fun collectResultingWhens(expression: KtExpression, result: MutableList<KtWhenExpression>) {
    when (expression) {
        is KtWhenExpression -> result.add(expression)
        is KtIfExpression -> {
            expression.then?.let { collectResultingWhens(it, result) }
            expression.`else`?.let { collectResultingWhens(it, result) }
        }
        else -> {}
    }
}
