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
package org.jetbrains.kotlin.highlighter.semanticanalyzer

import io.github.nbplugins.kotlin.nbm.highlighter.KaSemanticHighlightingVisitor
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.projectsextensions.KotlinProjectHelper.isScanning
import org.jetbrains.kotlin.language.Priorities
import org.netbeans.modules.csl.api.ColoringAttributes
import org.netbeans.modules.csl.api.OffsetRange
import org.netbeans.modules.csl.api.SemanticAnalyzer
import org.netbeans.modules.parsing.spi.Scheduler
import org.netbeans.modules.parsing.spi.SchedulerEvent

class KotlinSemanticAnalyzer : SemanticAnalyzer<KotlinParserResult>() {

    private var cancel = false
    private val highlighting = hashMapOf<OffsetRange, Set<ColoringAttributes>>()

    override fun getPriority() = Priorities.SEMANTIC_ANALYZER_PRIORITY

    override fun getHighlights() = highlighting

    override fun run(result: KotlinParserResult?, event: SchedulerEvent?) {
        highlighting.clear()
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
            highlighting.putAll(visitor.computeHighlightingRanges())
        }.onFailure { ex ->
            KotlinLogger.INSTANCE.logWarning("K2 semantic highlighting failed: $ex")
        }

        KotlinLogger.INSTANCE.logInfo("KotlinSemanticAnalyzer.run: produced ${highlighting.size} highlight ranges")
    }

    override fun cancel() {
        cancel = true
    }

    override fun getSchedulerClass(): Class<out Scheduler> = Scheduler.EDITOR_SENSITIVE_TASK_SCHEDULER

}
