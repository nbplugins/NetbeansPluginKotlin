/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.highlighter.semanticanalyzer

import io.github.nbplugins.kotlin.nbm.highlighter.KaSemanticHighlightingVisitor
import io.github.nbplugins.kotlin.nbm.highlighter.KotlinSemanticHighlightsLayerFactory
import io.github.nbplugins.kotlin.nbm.hover.KotlinTooltipHighlightsLayerFactory
import io.github.nbplugins.kotlin.nbm.diagnostics.parser.KotlinParserResult
import org.jetbrains.kotlin.log.KotlinLogger
import io.github.nbplugins.kotlin.nbm.projectsextensions.KotlinProjectHelper.isScanning
import org.jetbrains.kotlin.language.Priorities
import org.netbeans.modules.csl.api.ColoringAttributes
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.csl.api.SemanticAnalyzer
import org.netbeans.modules.parsing.spi.Scheduler
import org.netbeans.modules.parsing.spi.SchedulerEvent

/**
 * CSL [SemanticAnalyzer] that drives K2 semantic highlighting for Kotlin files.
 *
 * This class acts as the **computation trigger**: CSL calls [run] on each parse, which
 * computes highlight ranges via [KaSemanticHighlightingVisitor] and stores them in the
 * document as an [org.netbeans.lib.editor.util.swing.PositionsBag] managed by
 * [KotlinSemanticHighlightsLayerFactory]. The rendering is done by the layer factory, not
 * by this class — [getHighlights] therefore always returns an empty map.
 */
class KotlinSemanticAnalyzer : SemanticAnalyzer<KotlinParserResult>() {

    private var cancel = false

    override fun getPriority() = Priorities.SEMANTIC_ANALYZER_PRIORITY

    /** Always empty — rendering is delegated to [KotlinSemanticHighlightsLayerFactory]. */
    override fun getHighlights(): Map<OffsetRange, Set<ColoringAttributes>> = emptyMap()

    override fun run(result: KotlinParserResult?, event: SchedulerEvent?) {
        cancel = false

        KotlinLogger.INSTANCE.logInfo("KotlinSemanticAnalyzer.run: result=${result?.javaClass?.simpleName}")
        if (result == null) {
            KotlinLogger.INSTANCE.logWarning("KotlinSemanticAnalyzer.run: result is null")
            return
        }
        if (result.project.isScanning()) {
            KotlinLogger.INSTANCE.logInfo("KotlinSemanticAnalyzer.run: project is scanning, skip")
            return
        }

        val kaKtFile = result.kaKtFile
        if (kaKtFile == null) {
            KotlinLogger.INSTANCE.logWarning("KotlinSemanticAnalyzer.run: kaKtFile is null, skipping")
            return
        }

        runCatching {
            val visitor = KaSemanticHighlightingVisitor(kaKtFile)
            val highlights = visitor.computeHighlightingRanges()
            val doc = result.snapshot.source.getDocument(false)
            if (doc != null) {
                KotlinSemanticHighlightsLayerFactory.applyHighlights(doc, highlights)
                KotlinTooltipHighlightsLayerFactory.applyTooltipRanges(doc, highlights.keys)
            }
            KotlinLogger.INSTANCE.logInfo("KotlinSemanticAnalyzer.run: produced ${highlights.size} highlight ranges")
        }.onFailure { ex ->
            KotlinLogger.INSTANCE.logWarning("K2 semantic highlighting failed:\n${ex.stackTraceToString()}")
        }
    }

    override fun cancel() {
        cancel = true
    }

    override fun getSchedulerClass(): Class<out Scheduler> = Scheduler.EDITOR_SENSITIVE_TASK_SCHEDULER

}
