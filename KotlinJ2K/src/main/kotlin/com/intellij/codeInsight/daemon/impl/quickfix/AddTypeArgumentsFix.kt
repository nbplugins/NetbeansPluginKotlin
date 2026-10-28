/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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
package com.intellij.codeInsight.daemon.impl.quickfix

import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiType

/**
 * Standalone replacement for IDEA's `AddTypeArgumentsFix`.
 *
 * The real quick-fix infers explicit type arguments for a generic method call from the Java type
 * inference engine, which is not bundled. The J2K engine calls it to materialise explicit type
 * arguments before conversion (`JavaToJKTreeBuilder`, `K2TypeArgumentsExpander`).
 *
 * Returning `null` means "no type arguments could be inferred", and both call sites then keep the
 * original empty `typeArgumentList`. The Kotlin output therefore relies on Kotlin's own inference
 * at the call site, which handles the common cases.
 */
object AddTypeArgumentsFix {

    /**
     * Infers and inserts explicit type arguments in [expression].
     *
     * @param expression the method call to complete.
     * @param expectedType the expected type of the call, when known.
     * @param approve whether the change should be applied immediately.
     * @return always `null` — Java type inference is unavailable in standalone mode.
     */
    @JvmStatic
    fun addTypeArguments(
        expression: PsiMethodCallExpression,
        expectedType: PsiType?,
        approve: Boolean
    ): PsiExpression? = null
}
