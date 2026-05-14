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

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

/**
 * K2 Analysis API primary completion path.
 *
 * Returns the list of [KaDeclarationSymbol]s visible at [caretOffset] in [kaKtFile] whose
 * name starts with [prefix] (case-insensitive). The symbols are collected from the composite
 * scope at the caret position via `analyze { scopeContext(...).scopes }`.
 *
 * Must be called with a [KtFile] owned by a [io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession]
 * (not a K1 KtFile from [org.jetbrains.kotlin.model.KotlinEnvironment]).
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 */
object KaCompletionProvider {

    /**
     * Returns declaration symbols visible at [caretOffset] in [kaKtFile] that match [prefix].
     *
     * @param kaKtFile   K2-session-owned [KtFile] for the file being edited
     * @param caretOffset document offset of the caret
     * @param prefix     identifier prefix to filter by (case-insensitive); empty string returns all
     * @return filtered list of [KaDeclarationSymbol]s, or an empty list if analysis fails
     */
    fun getSymbolsAt(kaKtFile: KtFile, caretOffset: Int, prefix: String): List<KaDeclarationSymbol> =
        runCatching {
            analyze(kaKtFile) {
                val psi = kaKtFile.findElementAt(caretOffset)
                    ?: kaKtFile.findElementAt((caretOffset - 1).coerceAtLeast(0))
                    ?: return@analyze emptyList()
                val ktElement = generateSequence(psi) { it.parent }
                    .filterIsInstance<KtElement>()
                    .firstOrNull() ?: return@analyze emptyList()
                val compositeScope = kaKtFile.scopeContext(ktElement)
                    .scopes.map { it.scope }.asCompositeScope()
                compositeScope.declarations
                    .filter { sym ->
                        prefix.isEmpty() || (sym as? KaNamedSymbol)?.name?.identifier
                            ?.startsWith(prefix, ignoreCase = true) == true
                    }
                    .toList()
            }
        }.onFailure { e ->
            KotlinLogger.INSTANCE.logWarning(
                "K2 completion failed for ${kaKtFile.virtualFile?.path} at $caretOffset: $e"
            )
        }.getOrDefault(emptyList())
}
