/*******************************************************************************
 * Copyright 2000-2023 JetBrains s.r.o.
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
import org.jetbrains.kotlin.idea.codeinsight.utils.getRenderedTypeArguments
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * Inserts inferred type arguments explicitly on a call expression.
 *
 * For example, `listOf(1, 2)` becomes `listOf<Int>(1, 2)` when the type argument `Int` can be
 * inferred by the compiler and rendered as a short name.
 *
 * Applicable when the call expression has no explicit type arguments, has a callee expression, and
 * the K2 Analysis API can render the inferred type arguments.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaInsertExplicitTypeArgumentsIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private var cachedRendered: String? = null

    private fun findCallExpression(): KtCallExpression? =
        PsiTreeUtil.getParentOfType(psi, KtCallExpression::class.java, false)
            ?.takeIf { it.typeArguments.isEmpty() && it.calleeExpression != null }

    override fun isApplicable(caretOffset: Int): Boolean {
        val callExpr = findCallExpression() ?: return false
        val rendered = analyze(kaKtFile) { getRenderedTypeArguments(callExpr) } ?: return false
        cachedRendered = rendered
        return true
    }

    override fun getDescription(): String = "Add explicit type arguments"

    override fun implement() {
        val callExpr = findCallExpression() ?: return
        val rendered = cachedRendered
            ?: analyze(kaKtFile) { getRenderedTypeArguments(callExpr) }
            ?: return
        val callee = callExpr.calleeExpression ?: return
        // Insert the rendered type arguments (e.g. "<Int>") right after the callee name.
        val insertOffset = callee.textRange.endOffset
        doc.atomicChange {
            insertString(insertOffset, rendered, null)
        }
    }
}
