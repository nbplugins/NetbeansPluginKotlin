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
 * Custom [AbstractRefactoring] for the Kotlin Inline Variable (Ctrl+Alt+N) refactoring.
 *
 * Carries the editor document and caret offset in its [refactoringSource] lookup so that
 * [KotlinInlineVariablePlugin] can retrieve them during [prepare].
 *
 * @param doc    the document open in the editor at the time of invocation
 * @param offset caret position within [doc]
 */
class KotlinInlineVariableRefactoring(doc: StyledDocument, offset: Int) :
    AbstractRefactoring(Lookups.fixed(doc, offset))
