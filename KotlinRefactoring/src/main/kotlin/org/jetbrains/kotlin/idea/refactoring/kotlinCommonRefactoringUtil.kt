// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.unpackFunctionLiteral

/**
 * Minimal stub of kotlinCommonRefactoringUtil providing the three functions used
 * by the compiled inline-refactoring sources.  The rest of the original file uses
 * IDE-only types (ConflictsDialog, RootKindFilter, KtPsiClassWrapper) that are not
 * available in standalone NetBeans mode.
 */

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
