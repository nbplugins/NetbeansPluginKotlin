/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package org.jetbrains.kotlin.idea.codeinsight.utils

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression

/**
 * Stub for `KtCallableReferenceExpression.getReceiverKtType()`.
 *
 * Returns the type of the receiver expression if present, using the K2 Analysis API.
 * This replaces the IDE-internal extension of the same name in `code-insight/utils`.
 *
 * @receiver the callable reference expression to inspect
 * @return the type of the receiver expression, or null if there is no explicit receiver
 */
context(KaSession)
fun KtCallableReferenceExpression.getReceiverKtType(): KaType? =
    receiverExpression?.expressionType

/**
 * Stub for `Iterable<SmartPsiElementPointer<E>>.dereferenceValidPointers()`.
 *
 * Dereferences each smart pointer, discarding any that have become stale (i.e. whose
 * underlying element has been deleted from the PSI tree).
 *
 * @return list of live [E] elements, omitting stale pointers
 */
fun <E : com.intellij.psi.PsiElement> Iterable<com.intellij.psi.SmartPsiElementPointer<E>>.dereferenceValidPointers(): List<E> =
    mapNotNull { it.element }
