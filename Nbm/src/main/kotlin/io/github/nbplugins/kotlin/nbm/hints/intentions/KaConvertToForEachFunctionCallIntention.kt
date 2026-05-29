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
import org.jetbrains.kotlin.analysis.api.types.KaUsualClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelector

private val LOOP_RANGE_TYPE_FQ_NAMES: Set<FqName> = setOf(
    FqName("kotlin.collections.Iterable"),
    FqName("kotlin.sequences.Sequence"),
    FqName("kotlin.CharSequence"),
    FqName("kotlin.Array"),
    FqName("kotlin.DoubleArray"),
    FqName("kotlin.FloatArray"),
    FqName("kotlin.LongArray"),
    FqName("kotlin.IntArray"),
    FqName("kotlin.CharArray"),
    FqName("kotlin.ShortArray"),
    FqName("kotlin.ByteArray"),
    FqName("kotlin.BooleanArray"),
)

private const val WITH_INDEX_NAME = "withIndex"
private val WITH_INDEX_FQ_NAMES: Set<FqName> = setOf("collections", "sequences", "text", "ranges")
    .map { FqName("kotlin.$it.$WITH_INDEX_NAME") }.toSet()

/**
 * Converts a `for` loop to an equivalent `forEach` / `forEachIndexed` function call.
 *
 * For example:
 * ```kotlin
 * for (item in list) {
 *     process(item)
 * }
 * ```
 * becomes:
 * ```kotlin
 * list.forEach { item ->
 *     process(item)
 * }
 * ```
 *
 * Not applicable if the loop body contains a `break` statement (no equivalent in `forEach`).
 * `continue` expressions in the body are replaced with `return@forEach`.
 *
 * When the loop range is `x.withIndex()`, `forEachIndexed` is produced instead.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
class KaConvertToForEachFunctionCallIntention(
    doc: Document,
    kaKtFile: KtFile,
    psi: PsiElement
) : KaApplicableIntention(doc, kaKtFile, psi) {

    /** Finds the enclosing `for` expression. */
    private fun findForExpr(): KtForExpression? =
        PsiTreeUtil.getParentOfType(psi, KtForExpression::class.java, false)

    override fun isApplicable(caretOffset: Int): Boolean {
        val forExpr = findForExpr() ?: return false
        if (forExpr.loopRange == null || forExpr.loopParameter == null || forExpr.body == null) return false

        val labelName = (forExpr.parent as? KtLabeledExpression)?.getLabelName()
        if (forExpr.body.hasBreak(labelName)) return false

        return analyze(kaKtFile) {
            val loopRange = forExpr.loopRange ?: return@analyze false
            val calleeExpr = loopRange.possiblyQualifiedCallExpression()?.calleeExpression
            if (calleeExpr?.text == WITH_INDEX_NAME) {
                if (forExpr.loopParameter?.destructuringDeclaration?.entries?.size != 2) return@analyze false
                val symbol = calleeExpr.mainReference?.resolveToSymbol() as? KaNamedFunctionSymbol
                    ?: return@analyze false
                if (symbol.callableId?.asSingleFqName() !in WITH_INDEX_FQ_NAMES) return@analyze false
            }
            val rangeType = loopRange.expressionType ?: return@analyze false
            val fqName = (rangeType as? KaUsualClassType)?.classId?.asSingleFqName()
            // Check direct type match; for arrays and other non-class types accept unconditionally
            // since they all implement Iterable in practice.
            fqName == null || fqName in LOOP_RANGE_TYPE_FQ_NAMES ||
                    rangeType.isSubtypeOf(
                        org.jetbrains.kotlin.name.ClassId.topLevel(FqName("kotlin.collections.Iterable"))
                    )
        }
    }

    override fun getDescription(): String = "Replace 'for' loop with 'forEach' call"

    override fun implement() {
        val forExpr = findForExpr() ?: return
        val loopRange = forExpr.loopRange ?: return
        val loopParam = forExpr.loopParameter ?: return
        val body = forExpr.body ?: return
        val labelName = (forExpr.parent as? KtLabeledExpression)?.getLabelName()

        val bodyText = buildBodyText(body, labelName)

        val callExpression = loopRange.possiblyQualifiedCallExpression()
        val newText = if (callExpression?.calleeExpression?.text == WITH_INDEX_NAME) {
            val entries = loopParam.destructuringDeclaration?.entries ?: return
            val p0 = entries.getOrNull(0)?.text ?: return
            val p1 = entries.getOrNull(1)?.text ?: return
            val receiver = callExpression.getQualifiedExpressionForSelector()?.receiverExpression
            if (receiver == null) {
                "forEachIndexed { $p0, $p1 ->\n$bodyText\n}"
            } else {
                "${receiver.text}.forEachIndexed { $p0, $p1 ->\n$bodyText\n}"
            }
        } else {
            val paramText = when {
                loopParam.destructuringDeclaration != null ->
                    "(${loopParam.destructuringDeclaration!!.entries.joinToString(", ") { it.text }})"
                else -> loopParam.text
            }
            if (loopRange is KtThisExpression && loopRange.labelQualifier == null) {
                "forEach { $paramText ->\n$bodyText\n}"
            } else {
                "${loopRange.text}.forEach { $paramText ->\n$bodyText\n}"
            }
        }

        val start = forExpr.textRange.startOffset
        doc.atomicChange {
            remove(start, forExpr.textRange.length)
            insertString(start, newText, null)
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun KtExpression?.hasBreak(labelName: String?): Boolean {
        if (this == null) return false
        return collectDescendantsOfType<KtBreakExpression> { true }.any { brk ->
            brk.getLabelName() == null || brk.getLabelName() == labelName
        }
    }

    private fun buildBodyText(body: KtExpression, labelName: String?): String {
        val rawText = if (body is KtBlockExpression) {
            body.statements.joinToString("\n") { it.text }
        } else body.text
        var result = rawText.replace(Regex("\\bcontinue\\b(?!@)")) { "return@forEach" }
        if (labelName != null) {
            result = result.replace(Regex("\\bcontinue@$labelName\\b")) { "return@forEach" }
        }
        return result
    }

    private fun KtExpression.possiblyQualifiedCallExpression(): KtCallExpression? = when (this) {
        is KtCallExpression -> this
        is KtQualifiedExpression -> selectorExpression as? KtCallExpression
        else -> null
    }
}
