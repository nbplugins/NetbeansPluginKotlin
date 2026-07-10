// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.elementType
import com.intellij.refactoring.changeSignature.ChangeInfo
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getNextSiblingIgnoringWhitespaceAndComments
import org.jetbrains.kotlin.psi.psiUtil.getPrevSiblingIgnoringWhitespaceAndComments
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.siblings
import org.jetbrains.kotlin.psi.unpackFunctionLiteral
import kotlin.math.min

/**
 * Minimal stub of kotlinCommonRefactoringUtil providing the functions used
 * by the compiled inline/extract refactoring sources.  The rest of the original file uses
 * IDE-only types (ConflictsDialog, RootKindFilter, KtPsiClassWrapper) that are not
 * available in standalone NetBeans mode.
 */

/**
 * Creates a temporary in-memory copy of this file for analysis purposes.
 * Used by [org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.IExtractionData.createTemporaryCodeBlock].
 */
fun KtFile.createTempCopy(text: String? = null): KtFile {
    val tmpFile = KtPsiFactory.contextual(this).createFile(name, text ?: this.text ?: "")
    tmpFile.originalFile = this
    return tmpFile
}

/**
 * Adds [element] as the last statement of this block, before the closing brace. Copied verbatim
 * from IDEA's `kotlinCommonRefactoringUtil.kt`; used by `ExtractFunctionGenerator` to append the
 * default-value return expression for lazy-property/fake-lambda extraction targets.
 */
fun KtBlockExpression.addElement(element: KtElement, addNewLine: Boolean = false): KtElement {
    val rBrace = rBrace
    val newLine = KtPsiFactory(project).createNewLine()
    val anchor = if (rBrace == null) {
        val lastChild = lastChild
        lastChild as? PsiWhiteSpace ?: addAfter(newLine, lastChild)!!
    } else {
        rBrace.prevSibling!!
    }
    val addedElement = addAfter(element, anchor)!! as KtElement
    if (addNewLine) {
        addAfter(newLine, addedElement)
    }
    return addedElement
}

/** Returns the last trailing-lambda argument, or null if the call has lambda arguments already. */
fun KtCallExpression.getLastLambdaExpression(): KtLambdaExpression? {
    if (lambdaArguments.isNotEmpty()) return null
    return valueArguments.lastOrNull()?.getArgumentExpression()?.unpackFunctionLiteral()
}

/** Returns true when the call is complex enough that its lambda cannot be safely moved outside. */
fun KtCallExpression.isComplexCallWithLambdaArgument(): Boolean = when {
    valueArguments.lastOrNull()?.isNamed() == true -> true
    valueArguments.count { it.getArgumentExpression()?.unpackFunctionLiteral() != null } > 1 -> true
    else -> false
}

/**
 * Deletes this declaration.  If it is the sole member of a companion object (with no
 * super-type list), the entire companion object is deleted instead.
 */
fun KtNamedDeclaration.deleteWithCompanion() {
    val containingClass = this.containingClassOrObject
    if (containingClass is KtObjectDeclaration &&
        containingClass.isCompanion() &&
        containingClass.declarations.size == 1 &&
        containingClass.getSuperTypeList() == null
    ) {
        containingClass.delete()
    } else {
        this.delete()
    }
}

/**
 * Returns the element that determines a declaration's name. For a constructor this is the
 * containing class (constructors are named after their class); for everything else it is the
 * element itself.  Copied verbatim from IDEA's `kotlinCommonRefactoringUtil.kt`; used by
 * `KtReferenceMutateServiceBase` / `K2ReferenceMutateService` during reference rebinding.
 */
fun PsiElement.nameDeterminant() = when {
    this is KtConstructor<*> -> containingClass() ?: error("Constructor had no containing class")
    this is PsiMethod && isConstructor -> containingClass ?: error("Constructor had no containing class")
    else -> this
} as PsiNamedElement

/**
 * Moves a trailing function-literal argument outside the call's parentheses
 * (`foo({ … })` → `foo { … }`), preserving surrounding comments.  Copied verbatim from IDEA's
 * `kotlinCommonRefactoringUtil.kt`; used by the reference-mutate service after a rebind.
 */
fun KtCallExpression.moveFunctionLiteralOutsideParentheses(moveCaretTo: ((Int) -> Unit)? = null) {
    assert(lambdaArguments.isEmpty())
    val argumentList = valueArgumentList!!
    val arguments = argumentList.arguments
    val argument = arguments.last()
    val expression = argument.getArgumentExpression()!!
    assert(expression.unpackFunctionLiteral() != null)

    fun isWhiteSpaceOrComment(e: PsiElement) = e is PsiWhiteSpace || e is PsiComment

    val prevComma = argument.siblings(forward = false, withItself = false).firstOrNull { it.elementType == KtTokens.COMMA }
    val prevComments = (prevComma ?: argumentList.leftParenthesis)
        ?.siblings(forward = true, withItself = false)
        ?.takeWhile(::isWhiteSpaceOrComment)?.toList().orEmpty()
    val nextComments = argumentList.rightParenthesis
        ?.siblings(forward = false, withItself = false)
        ?.takeWhile(::isWhiteSpaceOrComment)?.toList()?.reversed().orEmpty()

    val psiFactory = KtPsiFactory(project)
    val dummyCall = psiFactory.createExpression("foo() {}") as KtCallExpression
    val functionLiteralArgument = dummyCall.lambdaArguments.single()
    functionLiteralArgument.getArgumentExpression()?.replace(expression)

    if (prevComments.any { it is PsiComment }) {
        if (prevComments.firstOrNull() !is PsiWhiteSpace) this.add(psiFactory.createWhiteSpace())
        prevComments.forEach { this.add(it) }
        prevComments.forEach { if (it is PsiComment) it.delete() }
    }
    this.add(functionLiteralArgument)
    if (nextComments.any { it is PsiComment }) {
        nextComments.forEach { this.add(it) }
        nextComments.forEach { if (it is PsiComment) it.delete() }
    }

    /* we should not remove empty parenthesis when callee is a call too - it won't parse */
    if (argumentList.arguments.size == 1 && calleeExpression !is KtCallExpression) {
        argumentList.delete()
        calleeExpression?.let { moveCaretTo?.invoke(it.endOffset) }
    } else {
        argumentList.removeArgument(argument)
        if (arguments.size > 1) {
            arguments[arguments.size - 2]?.let { moveCaretTo?.invoke(it.endOffset) }
        }
    }
}

/**
 * Returns the block/body of a declaration that a "caller body" check should search within.
 * Copied verbatim from IDEA's `kotlin.refactorings.common` `kotlinCommonRefactoringUtil.kt`;
 * used by Change Signature (E9.8) to distinguish a call site inside the function being changed
 * (which needs the parameter name, not its default value) from an outside caller.
 */
fun KtNamedDeclaration.getDeclarationBody(): KtElement? = when (this) {
    is KtClassOrObject -> getSuperTypeList()
    is KtPrimaryConstructor -> getContainingClassOrObject().getSuperTypeList()
    is KtSecondaryConstructor -> getDelegationCall()
    is KtNamedFunction -> bodyExpression
    else -> null
}

/**
 * True when this element lies inside the body of a declaration that [isCaller] recognizes as a
 * caller of the function being changed. Copied verbatim from IDEA's `kotlinCommonRefactoringUtil.kt`.
 */
fun KtElement.isInsideOfCallerBody(
    allUsages: Array<out UsageInfo>,
    isCaller: PsiElement.(Array<out UsageInfo>) -> Boolean
): Boolean {
    val container = parentsWithSelf.firstOrNull {
        it is KtNamedFunction || it is KtConstructor<*> || it is KtClassOrObject
    } as? KtNamedDeclaration ?: return false
    val body = container.getDeclarationBody() ?: return false
    return body.textRange.contains(textRange) && container.isCaller(allUsages)
}

/**
 * Replaces [originalList] with [newList] item-by-item, keeping delimiters/comments in place for the
 * common prefix instead of discarding the whole list. Copied verbatim from IDEA's
 * `kotlinCommonRefactoringUtil.kt`; used by Change Signature (E9.8) to update parameter lists
 * without losing surrounding trivia.
 */
fun <ListType : KtElement> replaceListPsiAndKeepDelimiters(
    changeInfo: ChangeInfo,
    originalList: ListType,
    newList: ListType,
    @Suppress("UNCHECKED_CAST") listReplacer: ListType.(ListType) -> ListType = { replace(it) as ListType },
    itemsFun: ListType.() -> List<KtElement>
): ListType {
    originalList.children.takeWhile { it is PsiErrorElement }.forEach { it.delete() }

    val oldParameters = originalList.itemsFun().toMutableList()
    val newParameters = newList.itemsFun()
    val oldCount = oldParameters.size
    val newCount = newParameters.size

    val commonCount = min(oldCount, newCount)
    val originalIndexes = changeInfo.newParameters.map { it.oldIndex }
    val keepComments = originalList.allChildren.any { it is PsiComment } &&
            oldCount > commonCount && originalIndexes == originalIndexes.sorted()
    if (!keepComments) {
        for (i in 0 until commonCount) {
            oldParameters[i] = oldParameters[i].replace(newParameters[i]) as KtElement
        }
    }

    if (commonCount == 0 && !keepComments) return originalList.listReplacer(newList)

    if (oldCount > commonCount) {
        if (keepComments) {
            ((0 until oldParameters.size) - originalIndexes).forEach { index ->
                val oldParameter = oldParameters[index]
                val nextComma = oldParameter.getNextSiblingIgnoringWhitespaceAndComments()?.takeIf { it.node.elementType == KtTokens.COMMA }
                if (nextComma != null) {
                    nextComma.delete()
                } else {
                    oldParameter.getPrevSiblingIgnoringWhitespaceAndComments()?.takeIf { it.node.elementType == KtTokens.COMMA }?.delete()
                }
                oldParameter.delete()
            }
        } else {
            originalList.deleteChildRange(oldParameters[commonCount - 1].nextSibling, oldParameters.last())
        }
    } else if (newCount > commonCount) {
        val lastOriginalParameter = oldParameters.last()
        val psiBeforeLastParameter = lastOriginalParameter.prevSibling
        val withMultiline =
            (psiBeforeLastParameter is PsiWhiteSpace || psiBeforeLastParameter is PsiComment) && psiBeforeLastParameter.textContains('\n')
        val extraSpace = if (withMultiline) KtPsiFactory(originalList.project).createNewLine() else null
        originalList.addRangeAfter(newParameters[commonCount - 1].nextSibling, newParameters.last(), lastOriginalParameter)
        if (extraSpace != null) {
            val addedItems = originalList.itemsFun().subList(commonCount, newCount)
            for (addedItem in addedItems) {
                val elementBefore = addedItem.prevSibling
                if ((elementBefore !is PsiWhiteSpace && elementBefore !is PsiComment) || !elementBefore.textContains('\n')) {
                    addedItem.parent.addBefore(extraSpace, addedItem)
                }
            }
        }
    }

    return originalList
}
