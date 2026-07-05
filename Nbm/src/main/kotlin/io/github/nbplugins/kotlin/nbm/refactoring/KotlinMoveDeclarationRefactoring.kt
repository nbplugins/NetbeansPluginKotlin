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

import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/**
 * Carrier [AbstractRefactoring] for the Kotlin **Move Declaration** refactoring (E9.7).
 *
 * After the UI dialog is confirmed, [targetPackage] and [targetFileName] hold the values chosen by
 * the user. Set by `KotlinMoveDeclarationUI.setParameters()`.
 *
 * @param doc          the document containing the declaration
 * @param caretOffset  caret position within [doc]
 */
class KotlinMoveDeclarationRefactoring(
    val doc: StyledDocument,
    val caretOffset: Int,
) : AbstractRefactoring(Lookups.fixed(doc)) {

    /** Target package (fully qualified, e.g. `"com.example.other"`), possibly unchanged. */
    var targetPackage: String = ""

    /** Target file name (just the simple name, e.g. `"Foo.kt"`). */
    var targetFileName: String = ""
}
