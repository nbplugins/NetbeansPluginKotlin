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
package io.github.nbplugins.kotlin.nbm.highlighter

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.idea.highlighting.highlighters.createBeforeResolveHighlightingAnalyzers
import org.jetbrains.kotlin.idea.highlighting.highlighters.createKotlinHighlightingAnalyzers
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.netbeans.modules.csl.api.OffsetRange

/**
 * Computes semantic highlighting ranges for a K2-owned [KtFile] using the K2 Analysis API.
 *
 * Delegates to IDEA's before-resolve and K2-resolve semantic highlighters, using a
 * [KaHighlightCapturingHolder] to intercept produced
 * [com.intellij.codeInsight.daemon.impl.HighlightInfo] objects. [KaHighlightColorMapper]
 * then converts them to color category name strings registered in `FontAndColors.xml`.
 *
 * Three passes are made:
 * 1. **Before-resolve** (no K2 analysis): declaration names (class, function, property, parameter,
 *    type parameter) and annotation entries — via [createBeforeResolveHighlightingAnalyzers].
 * 2. **K2-resolve** (inside [analyze]): function calls, type references, variable references
 *    — via [createKotlinHighlightingAnalyzers].
 * 3. **Deprecated diagnostics** (second [analyze]): deprecated-element highlights via
 *    [collectDiagnostics]; wrapped in [runCatching] because K2 2.0.21 throws
 *    [IllegalArgumentException] for some FIR elements (K2 bug: `FirIncompatibleClassExpressionChecker`).
 *
 * When multiple [com.intellij.codeInsight.daemon.impl.HighlightInfo] objects cover the same
 * source range (e.g. base property color + mutable-variable overlay), their color names are
 * **accumulated** in a list so that [KotlinSemanticHighlightsLayerFactory] can merge them
 * using [org.netbeans.api.editor.settings.FontColorSettings.getTokenFontColors].
 *
 * This class belongs to the **model/service** layer and must not reference NetBeans UI APIs.
 *
 * @param kaKtFile the K2-session-owned [KtFile] to highlight; must have been obtained from
 *   [io.github.nbplugins.kotlin.nbm.resolve.KotlinAnalysisAPISession.getKtFileForPath]
 */
class KaSemanticHighlightingVisitor(private val kaKtFile: KtFile) {

    /**
     * Runs all semantic highlight passes on [kaKtFile] and returns the full set of highlight ranges.
     *
     * @return map from [OffsetRange] to the ordered list of color category names for that range;
     *   names correspond to `<fontcolor name="...">` entries in `FontAndColors.xml`
     */
    fun computeHighlightingRanges(): Map<OffsetRange, List<String>> {
        val positions = hashMapOf<OffsetRange, MutableList<String>>()
        val holder = KaHighlightCapturingHolder(kaKtFile)

        runBeforeResolveHighlighters(holder)

        analyze(kaKtFile) {
            runKaHighlighters(holder)
        }

        holder.capturedInfos.forEach { info ->
            KaHighlightColorMapper.toFontColorName(info)?.let { name ->
                val range = OffsetRange(info.startOffset, info.endOffset)
                positions.getOrPut(range) { mutableListOf() }.add(name)
            }
        }

        runCatching {
            analyze(kaKtFile) {
                highlightDeprecated(positions)
            }
        }
        return positions
    }

    /**
     * Visits every PSI element in [kaKtFile] with before-resolve analyzers (no [KaSession] needed).
     *
     * Covers declaration-site highlights (class, function, property, parameter, type-parameter
     * names) and annotation entries.
     *
     * @param holder the capturing holder that receives produced highlight infos
     */
    private fun runBeforeResolveHighlighters(holder: KaHighlightCapturingHolder) {
        val analyzers = createBeforeResolveHighlightingAnalyzers(holder)
        kaKtFile.accept(object : KtVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                analyzers.forEach { element.accept(it) }
                element.acceptChildren(this)
            }
        })
    }

    /**
     * Visits every PSI element in [kaKtFile] with K2-resolve analyzers inside an active [KaSession].
     *
     * Covers function calls, type references, and variable/property references.
     *
     * @param holder the capturing holder that receives produced highlight infos
     */
    context(KaSession)
    private fun runKaHighlighters(holder: KaHighlightCapturingHolder) {
        val analyzers = createKotlinHighlightingAnalyzers(holder)
        kaKtFile.accept(object : KtVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                analyzers.forEach { element.accept(it) }
                element.acceptChildren(this)
            }
        })
    }

    /**
     * Collects K2 diagnostics for [kaKtFile] and appends "KOTLIN_DEPRECATED" to each deprecated
     * element's range list.
     *
     * @param positions accumulator for highlight ranges; the color name is appended to existing lists
     */
    context(KaSession)
    private fun highlightDeprecated(positions: HashMap<OffsetRange, MutableList<String>>) {
        kaKtFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)
            .filter { it.factoryName.contains("DEPRECATION", ignoreCase = true) }
            .forEach { diag ->
                diag.textRanges.forEach { range ->
                    val offsetRange = OffsetRange(range.startOffset, range.endOffset)
                    positions.getOrPut(offsetRange) { mutableListOf() }.add("KOTLIN_DEPRECATED")
                }
            }
    }
}
