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
@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.nbplugins.kotlin.nbm.completion

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.types.Variance
import org.netbeans.modules.csl.api.ParameterInfo

/**
 * K2 Analysis API provider for the Ctrl+P parameter-info popup in the Kotlin editor.
 *
 * Given a caret position inside a function-call argument list, resolves the callee via K2
 * and returns a [ParameterInfo] instance containing:
 * - the rendered parameter strings (name, type, optional default marker)
 * - the 0-based index of the parameter corresponding to the current cursor position
 * - the document offset of the opening parenthesis (used to anchor the popup)
 *
 * Returns [ParameterInfo.NONE] when the caret is not inside a resolvable call, when the
 * call is ambiguous, or when resolution fails for any reason.
 *
 * This object belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 */
object KaParameterInfoProvider {

    /**
     * Returns [ParameterInfo] for the function call at [caretOffset] in [kaKtFile],
     * or [ParameterInfo.NONE] when no resolvable call is found at that position.
     *
     * @param kaKtFile    K2-session-owned [KtFile] for the file being edited
     * @param caretOffset document offset of the caret (must point inside a call argument list)
     * @return populated [ParameterInfo], or [ParameterInfo.NONE]
     */
    fun getParameterInfo(kaKtFile: KtFile, caretOffset: Int): ParameterInfo =
        runCatching {
            analyze(kaKtFile) {
                val leaf = kaKtFile.findElementAt(caretOffset) ?: return@analyze ParameterInfo.NONE
                val argList = PsiTreeUtil.getParentOfType(leaf, KtValueArgumentList::class.java, false)
                    ?: return@analyze ParameterInfo.NONE
                val callElement = argList.parent as? KtCallElement ?: return@analyze ParameterInfo.NONE

                val functionSymbol = callElement.resolveToCall()?.singleFunctionCallOrNull()?.symbol
                    as? KaFunctionSymbol ?: return@analyze ParameterInfo.NONE

                val names = functionSymbol.valueParameters.map { renderParam(it) }
                if (names.isEmpty()) return@analyze ParameterInfo.NONE

                val currentIndex = argList.allChildren
                    .takeWhile { it.startOffset < caretOffset }
                    .count { it.node.elementType == KtTokens.COMMA }
                    .coerceIn(0, names.size - 1)

                val anchorOffset = argList.textRange.startOffset

                ParameterInfo(names, currentIndex, anchorOffset)
            }
        }.getOrElse { e ->
            KotlinLogger.INSTANCE.logException("KaParameterInfoProvider: resolution failed", e)
            ParameterInfo.NONE
        }

    /**
     * Renders a single value parameter as a display string: `[vararg ]name: Type[ = ...]`.
     *
     * The type is rendered using short names. A default value is indicated by the literal
     * suffix " = ..." rather than the actual default expression text, matching IDEA's style.
     */
    context(KaSession)
    private fun renderParam(param: KaValueParameterSymbol): String = buildString {
        if (param.isVararg) append("vararg ")
        append(param.name.identifier)
        append(": ")
        append(param.returnType.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT))
        if (param.hasDefaultValue) append(" = ...")
    }
}
