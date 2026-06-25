// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.util.application

import com.intellij.psi.PsiElement

/** Stub: executes the action directly (no write-action coordination needed in standalone mode). */
fun <T> runWriteActionIfPhysical(e: PsiElement, action: () -> T): T = action()

/** Stub: always returns false in standalone NetBeans mode (no unit-test EDT fixture). */
fun isUnitTestMode(): Boolean = false
