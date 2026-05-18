package org.jetbrains.kotlin.navigation.references

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtReferenceExpression

/** Returns the nearest [KtReferenceExpression] ancestor-or-self of [this], or null. */
fun PsiElement.getReferenceExpression(): KtReferenceExpression? =
    PsiTreeUtil.getNonStrictParentOfType(this, KtReferenceExpression::class.java)
