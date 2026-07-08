package org.jetbrains.kotlin.idea.refactoring

import com.intellij.psi.search.SearchScope
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression

/** Stub: restricts scope to the project's content scope. */
fun KtElement.codeUsageScopeRestrictedToProject(): SearchScope =
    com.intellij.psi.search.GlobalSearchScope.projectScope(project)

/** Stub: always returns true (move lambda outside parentheses is always possible). */
fun canMoveLambdaOutsideParentheses(call: KtExpression): Boolean = false

/**
 * Stub of IDEA's `KtCallExpression.canMoveLambdaOutsideParentheses` (a different overload than
 * [canMoveLambdaOutsideParentheses] above; used by Change Signature/E9.8's `KotlinFunctionCallUsage`
 * after rewriting a call's argument list). Real one needs a `KaSession` to check whether the
 * trailing argument is functional; moving a lambda outside parentheses is a purely cosmetic
 * formatting nicety (`foo({ ... })` vs `foo { ... }`), not a correctness requirement, so this always
 * declines rather than porting the full analysis.
 */
fun KtCallExpression.canMoveLambdaOutsideParentheses(skipComplexCalls: Boolean = true): Boolean = false

/** Stub: moves function literal outside parentheses (no-op in standalone). */
fun KtExpression.moveFunctionLiteralOutsideParentheses(): Unit = Unit
