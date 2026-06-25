// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

/** Stub for [TargetElementUtil]: cursor-element lookup not needed in standalone NetBeans mode. */
object TargetElementUtil {
    const val ELEMENT_NAME_ACCEPTED: Int = 1
    const val REFERENCED_ELEMENT_ACCEPTED: Int = 2

    /** Stub: always returns null in standalone mode. */
    @JvmStatic
    fun findTargetElement(editor: Editor?, flags: Int): PsiElement? = null

    /** Stub: always returns null in standalone mode (no editor-based reference lookup). */
    @JvmStatic
    fun findReference(editor: Editor, offset: Int): PsiReference? = null
}
