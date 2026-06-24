// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.codeInsight

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective

/** Stub for KotlinOptimizeImportsFacility: import optimization not needed in standalone NetBeans mode. */
interface KotlinOptimizeImportsFacility {
    data class AnalysisResult(val unusedImports: List<KtImportDirective>)

    /** Stub: returns null (no import analysis in standalone mode). */
    fun analyzeImports(file: KtFile): AnalysisResult?

    companion object {
        private val INSTANCE = object : KotlinOptimizeImportsFacility {
            override fun analyzeImports(file: KtFile): AnalysisResult? = null
        }

        /** Returns the stub singleton. */
        @JvmStatic
        fun getInstance(): KotlinOptimizeImportsFacility = INSTANCE
    }
}
