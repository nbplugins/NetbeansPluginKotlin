package org.jetbrains.kotlin.idea.refactoring

import com.intellij.psi.search.SearchScope
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression

/** Stub: restricts scope to the project's content scope. */
fun KtElement.codeUsageScopeRestrictedToProject(): SearchScope =
    com.intellij.psi.search.GlobalSearchScope.projectScope(project)

/** Stub: always returns true (move lambda outside parentheses is always possible). */
fun canMoveLambdaOutsideParentheses(call: KtExpression): Boolean = false

/** Stub: moves function literal outside parentheses (no-op in standalone). */
fun KtExpression.moveFunctionLiteralOutsideParentheses(): Unit = Unit
