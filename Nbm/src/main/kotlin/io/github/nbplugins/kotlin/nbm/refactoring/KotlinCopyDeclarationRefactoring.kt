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
 * Carrier [AbstractRefactoring] for the Kotlin **Copy Declaration** refactoring.
 *
 * After the UI dialog is confirmed, [targetRootPath], [targetPackage], and [targetFileName] hold
 * the destination selected by the user.
 *
 * @param doc          the document containing the declaration
 * @param caretOffset  caret position within [doc]
 */
class KotlinCopyDeclarationRefactoring(
    val doc: StyledDocument,
    val caretOffset: Int,
) : AbstractRefactoring(Lookups.fixed(doc)) {

    /** Target source-root path selected in the dialog. */
    var targetRootPath: String = ""

    /** Target package (fully qualified, e.g. `"com.example.other"`). */
    var targetPackage: String = ""

    /** Target file name (just the simple name, e.g. `"Foo.kt"`). */
    var targetFileName: String = ""
}
