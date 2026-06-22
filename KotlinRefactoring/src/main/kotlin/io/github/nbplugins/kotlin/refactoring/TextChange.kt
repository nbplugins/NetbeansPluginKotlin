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
package io.github.nbplugins.kotlin.refactoring

/**
 * A single text replacement in a source file: the half-open range [[start], [end]) is
 * replaced with [newText]; [oldText] is stored for undo support.
 *
 * All offsets are character offsets from the start of the file (0-based, as returned by
 * [com.intellij.psi.PsiElement.getTextRange]).
 *
 * @param start   inclusive start offset of the region to replace
 * @param end     exclusive end offset of the region to replace
 * @param newText text to write in place of the original range
 * @param oldText original text at [[start], [end]) — used to undo the change
 */
data class TextChange(
    val start: Int,
    val end: Int,
    val newText: String,
    val oldText: String,
)
