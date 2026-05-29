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
import org.jetbrains.kotlin.idea.codeinsight.utils.RemoveExplicitTypeArgumentsUtils
import org.jetbrains.kotlin.idea.k2.refactoring.util.areTypeArgumentsRedundant
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeArgumentList

/**
 * Removes explicit type arguments when they are redundant (the compiler can infer them).
 *
 * For example, `listOf<Int>(1, 2)` becomes `listOf(1, 2)` when the type argument `Int` can be
 * inferred from the arguments.
 *
 * Applicable when the caret is on a call expression with explicit type arguments that are provably
 * redundant according to the K2 Analysis API.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaRemoveExplicitTypeArgumentsIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement,
) : KaApplicableIntention(doc, kaKtFile, psi) {

    private fun findTypeArgumentList(): KtTypeArgumentList? {
        val typeArgList = PsiTreeUtil.getParentOfType(psi, KtTypeArgumentList::class.java, false)
        if (typeArgList != null) return typeArgList
        // Also try the call expression itself
        val callExpr = PsiTreeUtil.getParentOfType(psi, KtCallExpression::class.java, false)
            ?: return null
        return callExpr.typeArgumentList
    }

    override fun isApplicable(caretOffset: Int): Boolean {
        val typeArgList = findTypeArgumentList() ?: return false
        val callExpr = typeArgList.parent as? KtCallExpression ?: return false
        if (!RemoveExplicitTypeArgumentsUtils.isApplicableByPsi(callExpr)) return false
        return analyze(kaKtFile) { areTypeArgumentsRedundant(typeArgList) }
    }

    override fun getDescription(): String = "Remove explicit type arguments"

    override fun implement() {
        val typeArgList = findTypeArgumentList() ?: return
        val start = typeArgList.textRange.startOffset
        val length = typeArgList.textRange.length
        doc.atomicChange {
            remove(start, length)
        }
    }
}
