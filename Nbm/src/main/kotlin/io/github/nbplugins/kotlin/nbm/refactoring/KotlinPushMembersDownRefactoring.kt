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

/** Holds the source editor and selected member choices for Push Members Down. */
class KotlinPushMembersDownRefactoring(
    /** Document containing the source superclass or interface. */
    val document: StyledDocument,
    /** Caret offset at action invocation. */
    val caretOffset: Int,
) : AbstractRefactoring(Lookups.fixed(document)) {
    /** Stable source class offset resolved during discovery. */
    var sourceOffset: Int = caretOffset

    /** Stable offsets of members selected for push down. */
    var selectedOffsets: Set<Int> = emptySet()

    /** Selected members retained as abstract declarations in the source class. */
    var abstractOffsets: Set<Int> = emptySet()

    /** @return `true` when at least one member is selected for mutation. */
    fun isReady(): Boolean = selectedOffsets.isNotEmpty()
}
