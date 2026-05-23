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

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import org.jetbrains.kotlin.psi.KtFile

/**
 * A [HighlightInfoHolder] that intercepts every [HighlightInfo] added by the IDEA
 * semantic highlighters and stores it for later conversion to NetBeans coloring attributes.
 *
 * The holder is constructed with the [KtFile] as the PSI context file and an empty filter
 * array so that all highlights are accepted unconditionally.
 *
 * Instances of this class are not thread-safe and are intended for single-use within a
 * single [org.jetbrains.kotlin.analysis.api.analyze] block.
 *
 * @param ktFile the file being highlighted; used as the PSI context for the parent holder
 */
class KaHighlightCapturingHolder(ktFile: KtFile) : HighlightInfoHolder(ktFile) {

    /** All [HighlightInfo] objects produced by the IDEA semantic highlighters. */
    val capturedInfos: MutableList<HighlightInfo> = mutableListOf()

    /**
     * Intercepts the highlight and stores it in [capturedInfos] before delegating to the
     * parent. Returns `true` unconditionally so the caller considers the info accepted.
     *
     * @param info the highlight to capture; a `null` value (from an empty Builder) is ignored
     * @return `true` always
     */
    override fun add(info: HighlightInfo?): Boolean {
        if (info != null) capturedInfos.add(info)
        return true
    }
}
