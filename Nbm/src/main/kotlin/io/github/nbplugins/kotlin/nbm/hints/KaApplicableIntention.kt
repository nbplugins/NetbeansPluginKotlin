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
package io.github.nbplugins.kotlin.nbm.hints

import com.intellij.psi.PsiElement
import javax.swing.text.Document
import org.jetbrains.kotlin.psi.KtFile
import org.netbeans.modules.csl.api.HintFix

/**
 * K2 Analysis API equivalent of [org.jetbrains.kotlin.hints.intentions.ApplicableIntention].
 *
 * Subclasses call [org.jetbrains.kotlin.analysis.api.analyze] with [kaKtFile] inside
 * [isApplicable] and [implement] to query the K2 Analysis API.
 *
 * @param doc the Swing document for the file being edited
 * @param kaKtFile K2-session-owned [KtFile] for this file
 * @param psi PSI element at the caret position
 */
abstract class KaApplicableIntention(
    val doc: Document,
    val kaKtFile: KtFile,
    val psi: PsiElement
) : HintFix {

    /** Returns true if this intention is applicable at [caretOffset]. */
    abstract fun isApplicable(caretOffset: Int): Boolean

    override fun isSafe(): Boolean = true

    override fun isInteractive(): Boolean = false
}
