/*******************************************************************************
 * Copyright 2026 nbplugins contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
@file:Suppress("UnusedReceiverParameter")
package org.jetbrains.kotlin.idea.base.psi

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtElement

/**
 * Simplified `replaced` — replaces this element in the PSI tree and returns the replacement cast to [T].
 * Equivalent to the full version in `KotlinPsiModificationUtils.kt`; omits error-recovery logic
 * that depends on IDE-internal write-action utilities unavailable in standalone mode.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : PsiElement> PsiElement.replaced(newElement: T): T = replace(newElement) as T

/**
 * Stub for `KtElement.isInsideAnnotationEntryArgumentList()`.
 * Returns `false` — annotation-argument-list detection is not needed in standalone mode.
 * The only caller ([CallParameterInfoProvider]) skips annotation entries when `false`, which is correct.
 */
fun KtElement.isInsideAnnotationEntryArgumentList(): Boolean = false
