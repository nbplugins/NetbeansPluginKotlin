/*******************************************************************************
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
import io.github.nbplugins.kotlin.nbm.hints.KaApplicableIntention
import org.jetbrains.kotlin.idea.codeInsight.inspections.shared.RemoveEmptyParenthesesFromLambdaCallInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.ReplaceContainsInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.ReplaceGetOrSetInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.expressions.ReplaceCallWithBinaryOperatorInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.ReplaceCallWithUnaryOperatorIntention
import org.jetbrains.kotlin.idea.k2.codeinsight.intentions.ReplaceInvokeIntention
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtValueArgumentList
import javax.swing.text.Document

/**
 * Builds the F8 structural-conversion hints from the IDEA inspections and intentions
 * compiled by the `KotlinCodeInsightImpl` module.
 *
 * Each IDEA action is typed on a specific PSI element, so the element the caret refers to
 * must be looked up per conversion. These are the operator/function conversions:
 *
 * - `a.plus(b)` <-> `a + b`
 * - `a.get(i)` / `a.set(i, v)` <-> `a[i]` / `a[i] = v`
 * - `a.contains(b)` <-> `b in a`
 * - `a.invoke(b)` <-> `a(b)`
 * - `a.not()` <-> `!a`
 * - `f() {}` <-> `f {}`
 */
object KaStructuralConversionHints {

    /**
     * Returns the structural-conversion hints applicable at the element [psi].
     *
     * @param doc the Swing document for the file being edited
     * @param kaKtFile K2-session-owned [KtFile] for this file
     * @param psi PSI element at the caret position, belonging to [kaKtFile]
     * @return hints for every conversion whose target element encloses [psi]
     */
    fun hints(doc: Document, kaKtFile: KtFile, psi: PsiElement): List<KaApplicableIntention> = listOfNotNull(
        psi.ancestorOfType<KtDotQualifiedExpression>()?.let {
            KaIdeaIntentionHint(ReplaceInvokeIntention(), doc, kaKtFile, it)
        },
        psi.ancestorOfType<KtDotQualifiedExpression>()?.let {
            KaIdeaIntentionHint(ReplaceCallWithUnaryOperatorIntention(), doc, kaKtFile, it)
        },
        psi.ancestorOfType<KtDotQualifiedExpression>()?.let {
            KaIdeaInspectionHint(ReplaceCallWithBinaryOperatorInspection(), doc, kaKtFile, it)
        },
        psi.ancestorOfType<KtDotQualifiedExpression>()?.let {
            KaIdeaInspectionHint(ReplaceGetOrSetInspection(), doc, kaKtFile, it)
        },
        psi.ancestorOfType<KtDotQualifiedExpression>()?.let {
            KaIdeaInspectionHint(ReplaceContainsInspection(), doc, kaKtFile, it)
        },
        // The caret may sit just after the `)` that closes the argument list, so the
        // preceding leaf is searched too — matching IDEA, which anchors this inspection
        // on the argument list itself.
        (psi.ancestorOfType<KtValueArgumentList>() ?: psi.previousLeafOfType())?.let {
            KaIdeaInspectionHint(RemoveEmptyParenthesesFromLambdaCallInspection(), doc, kaKtFile, it)
        },
    )

    /** Walks up from [this] returning the first element of type [T], or null. */
    private inline fun <reified T : PsiElement> PsiElement.ancestorOfType(): T? {
        var current: PsiElement? = this
        while (current != null) {
            if (current is T) return current
            current = current.parent
        }
        return null
    }

    /**
     * Walks up from the leaf preceding [this].
     *
     * The `f() {}` -> `f {}` inspection is anchored on the [KtValueArgumentList], which the
     * caret can sit just outside of.
     */
    private fun PsiElement.previousLeafOfType(): KtValueArgumentList? {
        val previous = com.intellij.psi.util.PsiTreeUtil.prevLeaf(this) ?: return null
        return previous.ancestorOfType<KtValueArgumentList>()
    }
}
