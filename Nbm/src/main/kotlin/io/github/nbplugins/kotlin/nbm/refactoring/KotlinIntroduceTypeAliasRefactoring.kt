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
 * Carrier [AbstractRefactoring] for the Kotlin **Introduce Type Alias** refactoring.
 *
 * After the UI dialog is confirmed, [chosenName], [replaceAll], and [visibility] hold the values
 * the user entered.
 *
 * @param doc          the document under the caret when the action was invoked
 * @param caretOffset  caret position within [doc]
 */
class KotlinIntroduceTypeAliasRefactoring(
    val doc: StyledDocument,
    val caretOffset: Int,
) : AbstractRefactoring(Lookups.fixed(doc)) {

    /** Alias name chosen by the user in the dialog. */
    var chosenName: String = ""

    /**
     * When `true`, all textually identical type references in the file are replaced with the alias
     * name; when `false`, only the trigger reference is replaced.
     */
    var replaceAll: Boolean = true

    /**
     * Visibility modifier prefix for the `typealias` declaration (`"public"`, `"internal"`,
     * `"private"`, or `""` for the default public visibility).
     */
    var visibility: String = ""
}
