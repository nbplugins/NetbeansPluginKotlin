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
package io.github.nbplugins.kotlin.nbm.refactoring

import io.github.nbplugins.kotlin.refactoring.ScopeCandidate
import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/**
 * Carrier [AbstractRefactoring] for the Kotlin **Extract Function** refactoring.
 *
 * This subclass exists so [org.jetbrains.kotlin.refactorings.rename.KotlinRefactoringsFactory]
 * can identify the refactoring kind via `instanceof` and route it to
 * [KotlinExtractFunctionPlugin].
 *
 * After the UI dialog is confirmed, [chosenName] holds the function name the user entered.
 *
 * @param doc          the document under the editor when the action was invoked
 * @param startOffset  start of the selection within [doc]
 * @param endOffset    end of the selection (exclusive) within [doc]
 */
class KotlinExtractFunctionRefactoring(
    val doc: StyledDocument,
    val startOffset: Int,
    val endOffset: Int,
) : AbstractRefactoring(Lookups.fixed(doc)) {

    /** Function name chosen by the user in the dialog; written by [KotlinExtractFunctionUI.setParameters]. */
    var chosenName: String = ""

    /**
     * All valid extraction scopes collected before the dialog opens; populated by
     * [KotlinExtractFunctionAction] and read by [KotlinExtractFunctionApplyElement].
     */
    var scopeCandidates: List<ScopeCandidate> = emptyList()

    /** Index into [scopeCandidates] chosen by the user; 0 = innermost scope (default). */
    var chosenScopeIndex: Int = 0
}
