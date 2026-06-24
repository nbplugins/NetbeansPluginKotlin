package org.jetbrains.kotlin.idea.util

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

/** Stub: minimal isLineBreak check (matches FormatterUtil.kt in formatter/minimal). */
fun PsiElement?.isLineBreak(): Boolean = this is PsiWhiteSpace && StringUtil.containsLineBreak(text)
