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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.nbm.hints

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.hints.KotlinRule
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange

/**
 * K2 Analysis API port of [org.jetbrains.kotlin.hints.getSmartCastHover].
 *
 * Returns an info-level [Hint] when [expression] has a smart cast, using K2 symbol resolution
 * instead of [org.jetbrains.kotlin.resolve.BindingContext.SMARTCAST].
 *
 * @param expression the name expression to check for a smart cast
 * @param parserResult the parser result providing file metadata
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @return a hint describing the smart cast type, or null if no smart cast applies
 */
fun getSmartCastHoverK2(
    expression: KtSimpleNameExpression,
    parserResult: KotlinParserResult,
    kaKtFile: KtFile
): Hint? {
    val parent = expression.parent
    if (parent is KtThisExpression || parent is KtSuperExpression) return null

    val description = analyze(kaKtFile) {
        val ref = expression.references.filterIsInstance<KtReference>().firstOrNull() ?: return@analyze null
        ref.resolveToSymbol() ?: return@analyze null  // must resolve to something

        // K2 smart cast info: check if the expression has a smart cast type
        val smartCastType = expression.smartCastInfo?.smartCastType ?: return@analyze null
        "Smart cast to ${smartCastType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)}"
    } ?: return null

    return Hint(
        KotlinRule(HintSeverity.INFO),
        description,
        parserResult.snapshot.source.fileObject,
        OffsetRange(expression.textRange.startOffset, expression.textRange.endOffset),
        null,
        20
    )
}
