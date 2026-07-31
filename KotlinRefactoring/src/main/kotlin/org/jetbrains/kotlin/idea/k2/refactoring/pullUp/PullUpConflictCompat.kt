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
package org.jetbrains.kotlin.idea.k2.refactoring.pullUp

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.psi
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.idea.k2.refactoring.findCallableMemberBySignature

/**
 * Standalone presentation bridge for IDEA conflict messages.
 *
 * The upstream Push Down algorithm only needs a readable symbol name to report its own conflicts.
 * IDEA's full renderer also depends on UI-oriented rendering infrastructure absent from the embedded
 * platform, so the bridge retains the engine's conflict decisions while supplying a stable label.
 *
 * @param analysisSession active K2 analysis session, retained for IDEA-compatible call sites.
 * @return a readable declaration name.
 */
@OptIn(KaExperimentalApi::class)
internal fun KaSymbol.renderForConflicts(
    @Suppress("UNUSED_PARAMETER") analysisSession: KaSession,
): String = (this as? KaNamedSymbol)?.name?.asString() ?: psi?.text ?: "<anonymous>"

/** Gives copied IDEA sources access to analysis-symbol PSI in the 2.3.21 API. */
val KaSymbol.standalonePsi get() = psi

/**
 * Resolves a signature after K2 2.3.21 captures its concrete symbol type.
 *
 * @param signature callable signature produced by substitution or by a callable symbol.
 * @param ignoreReturnType whether signature matching ignores a differing return type.
 * @return a matching declaration in this target container, if one exists.
 */
@OptIn(KaExperimentalApi::class)
internal fun KaSession.findStandaloneCallableMemberBySignature(
    container: KaDeclarationContainerSymbol,
    signature: KaCallableSignature<*>,
    ignoreReturnType: Boolean,
): org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol? =
    findCallableMemberBySignature(container, signature, ignoreReturnType)
