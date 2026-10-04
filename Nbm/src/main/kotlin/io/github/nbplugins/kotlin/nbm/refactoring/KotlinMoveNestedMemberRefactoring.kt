/*******************************************************************************
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

import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/** Immutable target selection used by the move-nested-member controller and plugin. */
data class KotlinMoveNestedMemberTarget(
    /** Absolute path of the Kotlin file containing the selected target. */
    val filePath: String,
    /** Stable PSI offset inside [filePath] identifying the selected class/object. */
    val offset: Int,
)

/**
 * NetBeans refactoring carrier for one Kotlin Move Nested Member operation.
 *
 * @param document editor document containing the declaration at invocation time.
 * @param caretOffset caret position used to resolve the source declaration again before apply.
 */
class KotlinMoveNestedMemberRefactoring(
    /** Editor document containing the source declaration. */
    val document: StyledDocument,
    /** Caret position used to resolve the source declaration. */
    val caretOffset: Int,
) : AbstractRefactoring(Lookups.fixed(document)) {
    /** Absolute selected target-file path, populated by the passive target chooser. */
    var targetFilePath: String = ""

    /** Selected target class/object offset, populated by the passive target chooser. */
    var targetOffset: Int = -1

    /**
     * Returns the complete immutable target selection, or `null` when the dialog is incomplete.
     *
     * @return selected target file/offset when valid; otherwise `null`.
     */
    fun request(): KotlinMoveNestedMemberTarget? = targetFilePath.takeIf(String::isNotBlank)
        ?.let { path -> targetOffset.takeIf { it >= 0 }?.let { KotlinMoveNestedMemberTarget(path, it) } }
}
