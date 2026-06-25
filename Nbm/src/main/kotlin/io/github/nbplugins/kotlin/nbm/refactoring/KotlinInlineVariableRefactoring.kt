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
 * Carrier [AbstractRefactoring] for the Kotlin **Inline Variable** refactoring.
 *
 * The NetBeans refactoring framework dispatches every refactoring via an [AbstractRefactoring]
 * instance whose `getRefactoringSource()` Lookup carries the inputs the plugin needs. This
 * subclass exists purely so that [KotlinRefactoringsFactory] can recognise the refactoring kind
 * via `instanceof` and route it to [KotlinInlineVariablePlugin].
 *
 * The source Lookup contains:
 *  - the [StyledDocument] under the caret,
 *  - the caret offset as an [Int] (boxed as `Integer.valueOf(...)`).
 *
 * @param doc     the document under the caret when the action was invoked
 * @param offset  caret offset within [doc]
 */
class KotlinInlineVariableRefactoring(doc: StyledDocument, offset: Int) :
    AbstractRefactoring(Lookups.fixed(doc, Integer.valueOf(offset)))
