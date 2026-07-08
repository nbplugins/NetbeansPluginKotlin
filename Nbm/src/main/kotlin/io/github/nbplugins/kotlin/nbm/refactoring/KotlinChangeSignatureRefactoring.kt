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

import io.github.nbplugins.kotlin.refactoring.KaChangeSignatureRequest
import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/**
 * Carrier [AbstractRefactoring] for the Kotlin **Change Signature** refactoring (E9.8).
 *
 * After the UI dialog is confirmed, [request] holds the user-edited signature (name, return type,
 * and parameter list) to apply. Set by `KotlinChangeSignatureUI.setParameters()`.
 *
 * @param doc          the document containing the declaration
 * @param caretOffset  caret position within [doc]
 */
class KotlinChangeSignatureRefactoring(
    val doc: StyledDocument,
    val caretOffset: Int,
) : AbstractRefactoring(Lookups.fixed(doc)) {

    /** The user-edited signature; set once the dialog is confirmed. */
    var request: KaChangeSignatureRequest? = null
}
