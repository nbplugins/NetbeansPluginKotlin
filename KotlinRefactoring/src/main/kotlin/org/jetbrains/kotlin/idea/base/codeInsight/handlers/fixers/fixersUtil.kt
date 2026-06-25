// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Stub: provides PsiElement.range, TextRange.start, TextRange.end used by RedundantExplicitTypeArgumentsUtil.kt.

package org.jetbrains.kotlin.idea.base.codeInsight.handlers.fixers

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

val PsiElement.range: TextRange get() = textRange ?: TextRange.EMPTY_RANGE
val TextRange.start: Int get() = startOffset
val TextRange.end: Int get() = endOffset
