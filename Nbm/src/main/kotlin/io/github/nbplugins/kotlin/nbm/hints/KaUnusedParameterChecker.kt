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
package io.github.nbplugins.kotlin.nbm.hints

import com.intellij.psi.util.PsiTreeUtil
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.hints.KotlinRule
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.netbeans.modules.csl.api.Hint
import org.netbeans.modules.csl.api.HintSeverity
import org.netbeans.modules.csl.api.OffsetRange
import org.openide.filesystems.FileObject

/**
 * Detects unused parameters in private and local Kotlin functions using the K2 Analysis API.
 *
 * K2's `collectDiagnostics` does not expose `UNUSED_PARAMETER` for named function parameters
 * (only `UNUSED_VARIABLE` for locals is available as a compiler diagnostic). This checker
 * replicates the detection via PSI walking and K2 reference resolution.
 *
 * Checked functions:
 * - Top-level `private` functions
 * - `private` member functions (not `override`, not `abstract`)
 * - Local functions (defined inside another function body)
 *
 * Excluded:
 * - Parameters whose name starts with `_` (conventionally unused)
 * - Parameters in functions annotated with `@Suppress("UNUSED_PARAMETER")`
 * - Override / abstract / expected functions (parameter required by contract)
 *
 * @param parserResult the parser result providing file metadata
 * @param kaKtFile K2-session-owned [KtFile]; references are resolved against it
 */
class KaUnusedParameterChecker(
    private val parserResult: KotlinParserResult,
    private val kaKtFile: KtFile,
) {

    /**
     * Returns a [Hint] for each parameter of an eligible function that is never referenced
     * in the function body.
     */
    fun getUnusedParameterHints(): List<Hint> {
        val fileObject = parserResult.snapshot?.source?.fileObject ?: return emptyList()
        val result = mutableListOf<Hint>()
        runCatching {
            analyze(kaKtFile) {
                kaKtFile.accept(object : KtTreeVisitorVoid() {
                    override fun visitNamedFunction(function: KtNamedFunction) {
                        super.visitNamedFunction(function)
                        if (isEligible(function)) {
                            result += unusedParamHints(function, fileObject)
                        }
                    }
                })
            }
        }.onFailure {
            KotlinLogger.INSTANCE.logWarning(
                "KaUnusedParameterChecker: analysis failed for ${kaKtFile.name}: $it"
            )
        }
        return result
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun isEligible(function: KtNamedFunction): Boolean {
        if (function.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return false
        if (function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false
        if (function.hasModifier(KtTokens.OPEN_KEYWORD)) return false
        if (function.hasModifier(KtTokens.EXPECT_KEYWORD)) return false
        val isLocal = function.parent is KtBlockExpression
        val isPrivate = function.hasModifier(KtTokens.PRIVATE_KEYWORD)
        return isLocal || isPrivate
    }

    context(KaSession)
    private fun unusedParamHints(function: KtNamedFunction, fileObject: FileObject): List<Hint> {
        val params = function.valueParameters.filter { !isSuppressed(it) }
        if (params.isEmpty()) return emptyList()

        val body = function.bodyExpression ?: function.bodyBlockExpression ?: return emptyList()

        // Collect all PSI elements that name references resolve to inside the function body
        val resolvedPsi = PsiTreeUtil
            .collectElementsOfType(body, KtSimpleNameExpression::class.java)
            .mapNotNull { expr ->
                expr.references.filterIsInstance<KtReference>().firstOrNull()
                    ?.resolveToSymbol()?.psi
            }.toSet()

        return params
            .filter { param -> param !in resolvedPsi }
            .map { param ->
                Hint(
                    KotlinRule(HintSeverity.WARNING),
                    "Parameter '${param.name}' is never used",
                    fileObject,
                    OffsetRange(param.textRange.startOffset, param.textRange.endOffset),
                    emptyList(),
                    30
                )
            }
    }

    /** Parameter is suppressed if it starts with `_` or has a `@Suppress("UNUSED_PARAMETER")`. */
    private fun isSuppressed(param: KtParameter): Boolean {
        if (param.name?.startsWith("_") == true) return true
        return param.annotationEntries.any { annotation ->
            annotation.text.contains("UNUSED_PARAMETER")
        }
    }
}
