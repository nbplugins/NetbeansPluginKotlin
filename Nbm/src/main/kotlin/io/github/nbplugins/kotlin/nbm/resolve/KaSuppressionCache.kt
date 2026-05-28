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
package io.github.nbplugins.kotlin.nbm.resolve

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

/**
 * K2-based suppression annotation lookup.
 *
 * Returns the fully-qualified names of all annotations on a given PSI element using
 * the K2 Analysis API. Used by [org.jetbrains.kotlin.resolve.KotlinCacheServiceImpl]
 * as the primary suppression-check path (K1 `BindingContext`-based path kept as fallback).
 */
object KaSuppressionCache {

    /**
     * Returns the FQNs of all annotations on [annotated] resolved via the K2 Analysis API,
     * or an empty list if [kaKtFile] is `null`, [annotated] is not a [KtAnnotated], or
     * analysis fails.
     *
     * @param annotated  the PSI element whose annotations are needed
     * @param kaKtFile   K2-session-owned [KtFile] for the file containing [annotated];
     *                   obtain via [KotlinAnalysisAPISession.getKtFileForPath]
     * @return annotation FQNs (e.g. `"kotlin.Suppress"`), possibly empty
     */
    fun getAnnotationFqNames(annotated: PsiElement, kaKtFile: KtFile?): List<String> {
        if (annotated !is KtAnnotated || kaKtFile == null) return emptyList()
        return runCatching {
            analyze(kaKtFile) {
                val symbol = (annotated as? KtDeclaration)?.symbol ?: return@analyze emptyList()
                symbol.annotations.mapNotNull { it.classId?.asSingleFqName()?.asString() }
            }
        }.getOrDefault(emptyList())
    }
}
