/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints.fixes

import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintFix

/**
 * K2 Analysis API equivalent of [org.jetbrains.kotlin.hints.fixes.KotlinQuickFix].
 *
 * Implementors call [org.jetbrains.kotlin.analysis.api.analyze] to perform K2-based checks.
 */
interface KaQuickFix : HintFix {

    /** Returns true if this fix is applicable to the associated diagnostic. */
    fun isApplicable(): Boolean

    /** Creates the [Hint] wrapping this fix for the hint list. */
    fun createHint(): Hint

    /**
     * Creates the [Hint] wrapping [fix] instead of `this` in the fix list.
     * Used by [KotlinHintsProvider] to wrap the fix with post-apply invalidation logic.
     */
    fun createHintWith(fix: HintFix): Hint

    override fun isSafe(): Boolean = true

    override fun isInteractive(): Boolean = false
}
