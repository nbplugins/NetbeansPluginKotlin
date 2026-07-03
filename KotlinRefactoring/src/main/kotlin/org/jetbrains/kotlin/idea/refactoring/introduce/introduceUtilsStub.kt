// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring.introduce

import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.idea.base.psi.dropCurlyBracketsIfPossible
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.psiUtil.PsiChildRange
import org.jetbrains.kotlin.resolve.calls.util.getCalleeExpressionIfAny
import org.jetbrains.kotlin.utils.getElementTextWithContext

/**
 * Minimal subset of IDEA's `introduceUtils.kt` (kotlin.refactorings.common) needed by
 * `ExtractFunctionGenerator`. The rest of that file uses IDE-only types (`Editor`,
 * `CommonRefactoringUtil`, `chooseContainerElementIfNecessary`) not available standalone.
 */

/** Copied verbatim from IDEA's introduceUtils.kt. */
fun KtExpression.removeTemplateEntryBracesIfPossible(): KtExpression {
    val parent = parent as? KtBlockStringTemplateEntry ?: return this
    return parent.dropCurlyBracketsIfPossible().expression!!
}

/** Copied verbatim from IDEA's introduceUtils.kt. */
fun KtExpression.mustBeParenthesizedInInitializerPosition(): Boolean {
    if (this !is KtBinaryExpression) return false

    if (left?.mustBeParenthesizedInInitializerPosition() == true) return true
    return PsiChildRange(left, operationReference).any { (it is PsiWhiteSpace) && it.textContains('\n') }
}

/** Copied verbatim from IDEA's introduceUtils.kt. */
fun KtExpression.getContainingLambdaOutsideParentheses(): KtLambdaArgument? {
    val parent = parent
    return when (parent) {
        is KtLambdaArgument -> parent
        is KtLabeledExpression -> parent.getContainingLambdaOutsideParentheses()
        else -> null
    }
}

/** Copied verbatim from IDEA's introduceUtils.kt. */
fun KtNamedDeclaration.getGeneratedBody(): KtExpression =
    when (this) {
        is KtNamedFunction -> bodyExpression
        else -> {
            val property = this as KtProperty

            property.getter?.bodyExpression?.let { return it }
            property.initializer?.let { return it }
            // We assume lazy property here with delegate expression 'by Delegates.lazy { body }'
            property.delegateExpression?.let {
                val call = it.getCalleeExpressionIfAny()?.parent as? KtCallExpression
                call?.lambdaArguments?.singleOrNull()?.getLambdaExpression()?.bodyExpression
            }
        }
    } ?: throw AssertionError("Couldn't get block body for this declaration: ${getElementTextWithContext(this)}")

/** Copied verbatim from IDEA's introduceUtils.kt. */
fun ExtractableSubstringInfo.replaceWith(replacement: KtExpression): KtExpression {
    val psiFactory = KtPsiFactory(replacement.project)
    val parent = startEntry.parent

    psiFactory.createStringTemplate(prefix).entries.singleOrNull()?.let { parent.addBefore(it, startEntry) }

    val refEntry = createStringTemplateEntryFromExpression(replacement, psiFactory)
    val addedRefEntry = parent.addBefore(refEntry, startEntry) as KtStringTemplateEntryWithExpression

    psiFactory.createStringTemplate(suffix).entries.singleOrNull()?.let { parent.addAfter(it, endEntry) }

    parent.deleteChildRange(startEntry, endEntry)

    return addedRefEntry.expression!!
}

private fun ExtractableSubstringInfo.createStringTemplateEntryFromExpression(
    replacement: KtExpression,
    psiFactory: KtPsiFactory
): KtStringTemplateEntryWithExpression {
    val interpolationPrefix = template.interpolationPrefix
    return if (interpolationPrefix != null) {
        psiFactory.createMultiDollarBlockStringTemplateEntry(replacement, prefixLength = interpolationPrefix.textLength)
    } else {
        psiFactory.createBlockStringTemplateEntry(replacement)
    }
}
