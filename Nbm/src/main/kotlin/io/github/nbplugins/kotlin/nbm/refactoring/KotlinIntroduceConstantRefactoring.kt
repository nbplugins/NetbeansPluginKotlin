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

import io.github.nbplugins.kotlin.refactoring.ConstantDestination
import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/**
 * Carrier [AbstractRefactoring] for the Kotlin **Introduce Constant** refactoring.
 *
 * After the UI dialog is confirmed, [chosenName], [replaceAll], and [destination] hold the values
 * the user entered.
 *
 * @param doc          the document under the caret when the action was invoked
 * @param startOffset  caret position (or selection start) within [doc]
 * @param endOffset    selection end (exclusive); equals [startOffset] when there is no selection
 */
class KotlinIntroduceConstantRefactoring(
    val doc: StyledDocument,
    val startOffset: Int,
    val endOffset: Int,
) : AbstractRefactoring(Lookups.fixed(doc)) {

    /** Constant name chosen by the user in the dialog; set by [KotlinIntroduceConstantUI.setParameters]. */
    var chosenName: String = ""

    /** When true, all semantically equivalent occurrences are replaced; when false, only the selected expression. */
    var replaceAll: Boolean = true

    /**
     * Destination chosen by the user: [ConstantDestination.COMPANION_OBJECT] or
     * [ConstantDestination.TOP_LEVEL]; set by [KotlinIntroduceConstantUI.setParameters].
     */
    var destination: ConstantDestination = ConstantDestination.TOP_LEVEL
}
