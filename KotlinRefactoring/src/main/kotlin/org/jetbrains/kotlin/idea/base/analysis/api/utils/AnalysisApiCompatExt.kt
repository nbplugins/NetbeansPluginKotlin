// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(kotlin.ExperimentalContextParameters::class)
package org.jetbrains.kotlin.idea.base.analysis.api.utils

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.*
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaSubtypingErrorTypePolicy
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaSmartCastedReceiverValue
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtElement

/** Backward-compat: was [KaType.isNotSubTypeOf] in 2.0.x, now [!isSubtypeOf]. */
context(_: KaSession)
fun KaType.isNotSubTypeOf(supertype: KaType, policy: KaSubtypingErrorTypePolicy = KaSubtypingErrorTypePolicy.STRICT): Boolean =
    !isSubtypeOf(supertype, policy)

/** Stub: in IDEA runs analysis inside a modal progress window; here runs directly. */
inline fun <R> analyzeInModalWindow(contextElement: KtElement, @Suppress("UNUSED_PARAMETER") windowTitle: String, crossinline action: KaSession.() -> R): R =
    analyze(contextElement, action)

/** Unwraps smart-cast receiver values; from resolveUtils.kt (not on standalone classpath). */
tailrec fun KaReceiverValue.unwrapSmartCasts(): KaReceiverValue = when (this) {
    is KaSmartCastedReceiverValue -> original.unwrapSmartCasts()
    else -> this
}

/**
 * Copied verbatim from IDEA's `analyzeUtils.kt` (not on standalone classpath): runs [action] with
 * both write-action and EDT analysis permission opted in, then a normal [analyze] block. Used by
 * Change Signature (E9.8)'s usage-info classes, which run PSI mutation and analysis interleaved.
 */
@OptIn(
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
    org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisFromWriteAction::class,
    org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt::class,
)
inline fun <T : KtElement, R> allowAnalysisFromWriteActionInEdt(
    useSiteElement: T,
    action: KaSession.(T) -> R,
): R = org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt {
    org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisFromWriteAction {
        analyze(useSiteElement) {
            action(useSiteElement)
        }
    }
}
