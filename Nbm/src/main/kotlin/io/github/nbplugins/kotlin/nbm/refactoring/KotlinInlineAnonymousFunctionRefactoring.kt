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

/**
 * Carries the active document and caret through NetBeans Inline Anonymous Function UI.
 *
 * @param doc Kotlin editor document containing the immediately invoked lambda or anonymous function
 * @param offset caret offset within [doc]
 */
class KotlinInlineAnonymousFunctionRefactoring(
    val doc: StyledDocument,
    val offset: Int,
) : AbstractRefactoring(Lookups.fixed(doc, Integer.valueOf(offset)))
