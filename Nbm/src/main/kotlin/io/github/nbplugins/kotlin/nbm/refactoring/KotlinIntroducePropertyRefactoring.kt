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

import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/**
 * Carrier [AbstractRefactoring] for the Kotlin **Introduce Property** refactoring.
 *
 * After the UI dialog is confirmed, [chosenName] and [useVar] hold the values the user entered.
 * The [targetSiblingOffset] is set when the user picks a specific scope from the dialog combo box
 * (defaults to `null`, meaning the innermost valid class-body or file scope is used).
 *
 * @param doc                the document under the caret when the action was invoked
 * @param startOffset        start of the selection within [doc]
 * @param endOffset          end of the selection (exclusive); equals [startOffset] when there is no selection
 */
class KotlinIntroducePropertyRefactoring(
    val doc: StyledDocument,
    val startOffset: Int,
    val endOffset: Int,
) : AbstractRefactoring(Lookups.fixed(doc)) {

    /** Property name chosen by the user in the dialog. */
    var chosenName: String = ""

    /** When `true`, the introduced declaration uses `var`; when `false` (default), it uses `val`. */
    var useVar: Boolean = false

    /**
     * Start offset of the target sibling element chosen by the user (scope combo box),
     * or `null` to use the innermost valid class-body / file scope.
     */
    var targetSiblingOffset: Int? = null
}
