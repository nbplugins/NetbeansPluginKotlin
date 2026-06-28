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
 * Carrier [AbstractRefactoring] for the Kotlin **Introduce Import Alias** refactoring.
 *
 * After the UI dialog is confirmed, [chosenAlias] holds the alias name entered by the user.
 *
 * @param doc          the document under the caret when the action was invoked
 * @param caretOffset  the caret position within [doc]
 */
class KotlinIntroduceImportAliasRefactoring(
    val doc: StyledDocument,
    val caretOffset: Int,
) : AbstractRefactoring(Lookups.fixed(doc)) {

    /** Alias name chosen by the user; set by [KotlinIntroduceImportAliasUI.setParameters]. */
    var chosenAlias: String = ""
}
