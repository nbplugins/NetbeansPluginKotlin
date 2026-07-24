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

import io.github.nbplugins.kotlin.refactoring.ExtractSuperKind
import io.github.nbplugins.kotlin.refactoring.ExtractSuperRequest
import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.util.lookup.Lookups
import javax.swing.text.StyledDocument

/** Carrier for NetBeans choices made by the Extract Interface/Superclass dialog. */
class KotlinExtractSuperRefactoring(
    /** Document that contains the original class. */
    val document: StyledDocument,
    /** Invocation caret in [document]. */
    val caretOffset: Int,
    /** Whether this invocation extracts an interface or superclass. */
    val kind: ExtractSuperKind,
) : AbstractRefactoring(Lookups.fixed(document)) {
    /** Start offset of the source class discovered before opening the dialog. */
    var classOffset: Int = caretOffset

    /** Name of the newly extracted Kotlin type. */
    var extractedName: String = ""

    /** Kotlin source filename into which the extracted type is written. */
    var targetFileName: String = ""

    /** Path of the source root below which the target package directory is created. */
    var targetRootPath: String = ""

    /** Fully-qualified target package, empty for the default package. */
    var targetPackage: String = ""

    /** Offsets of real IDEA member candidates selected in the dialog. */
    var selectedOffsets: Set<Int> = emptySet()

    /** Selected members which the user requests as abstract. */
    var abstractOffsets: Set<Int> = emptySet()

    /** @return immutable backend request, or `null` until required choices are complete. */
    fun request(): ExtractSuperRequest? = extractedName.trim().takeIf { it.isNotEmpty() }?.let { name ->
        ExtractSuperRequest(
            classOffset,
            name,
            kind,
            selectedOffsets,
            abstractOffsets,
            targetFileName.trim(),
            targetPackage.trim(),
        )
    }
}
