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
package io.github.nbplugins.kotlin.nbm.completion

import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.utils.KotlinImageProvider
import org.netbeans.modules.csl.api.ElementKind

/**
 * Converts K2 [KaDeclarationSymbol]s to NetBeans [KaCompletionProposal]s.
 *
 * Symbol-to-proposal mapping:
 * - [KaFunctionSymbol] → [ElementKind.METHOD], `()` appended on insertion
 * - [KaPropertySymbol] → [ElementKind.FIELD]
 * - [KaClassLikeSymbol] → [ElementKind.CLASS]
 * - Other → [ElementKind.OTHER]
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs
 * other than [org.netbeans.modules.csl.api].
 */
object KaCompletionProposalFactory {

    /**
     * Converts [symbol] to a [KaCompletionProposal], or `null` if the symbol has no name.
     *
     * @param symbol      the K2 declaration symbol to wrap
     * @param anchorOffset document offset where the identifier starts
     * @param prefix      already-typed prefix; removed on proposal insertion
     * @return a [KaCompletionProposal], or `null` for anonymous symbols
     */
    fun toProposal(symbol: KaDeclarationSymbol, anchorOffset: Int, prefix: String): KaCompletionProposal? {
        val name = (symbol as? KaNamedSymbol)?.name?.identifier ?: return null
        val (kind, icon, isFunctionLike) = when (symbol) {
            is KaFunctionSymbol -> Triple(
                ElementKind.METHOD,
                KotlinImageProvider.functionImage,
                true,
            )
            is KaPropertySymbol -> Triple(
                ElementKind.FIELD,
                KotlinImageProvider.typeImage,
                false,
            )
            is KaClassLikeSymbol -> Triple(
                ElementKind.CLASS,
                KotlinImageProvider.typeImage,
                false,
            )
            else -> Triple(ElementKind.OTHER, null, false)
        }
        return KaCompletionProposal(name, kind, icon, anchorOffset, prefix, isFunctionLike)
    }
}
