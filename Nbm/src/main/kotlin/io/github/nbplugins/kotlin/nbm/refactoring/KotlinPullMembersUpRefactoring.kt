/*******************************************************************************
 * Copyright 2000-2025 JetBrains s.r.o.
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

import io.github.nbplugins.kotlin.refactoring.PullMembersUpRequest
import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/** Holds the editor and member-selection choices for a Pull Members Up operation. */
class KotlinPullMembersUpRefactoring(
    /** Source document containing the subclass. */
    val document: StyledDocument,
    /** Caret offset at which the action was invoked. */
    val caretOffset: Int,
) : AbstractRefactoring(Lookups.fixed(document)) {
    /** Stable offset of the source subclass. */
    var sourceOffset: Int = caretOffset

    /** Stable offset of the selected direct supertype. */
    var targetOffset: Int = -1

    /** Absolute virtual-file path containing the selected target supertype. */
    var targetFilePath: String = ""

    /** Stable offsets of selected source members. */
    var selectedOffsets: Set<Int> = emptySet()

    /** Selected members requested as abstract declarations. */
    var abstractOffsets: Set<Int> = emptySet()

    /** Whether the current target/member selection completed a conflict preview. */
    var conflictsPreviewed: Boolean = false

    /**
     * Converts complete dialog choices to an immutable engine request.
     *
     * @return request when a target and at least one member are selected; otherwise `null`.
     */
    fun request(): PullMembersUpRequest? = targetOffset.takeIf { it >= 0 }?.let { target ->
        targetFilePath.takeIf(String::isNotBlank)?.let { path ->
            selectedOffsets.takeIf(Set<Int>::isNotEmpty)?.let { selected ->
                PullMembersUpRequest(sourceOffset, target, path, selected, abstractOffsets.intersect(selected))
            }
        }
    }
}
