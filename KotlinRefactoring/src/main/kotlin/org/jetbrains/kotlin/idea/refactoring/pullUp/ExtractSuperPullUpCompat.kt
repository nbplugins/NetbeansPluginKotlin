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
package org.jetbrains.kotlin.idea.refactoring.pullUp

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter

/**
 * Determines whether [member] is still used by the source class after selected declarations move.
 *
 * Standalone NetBeans does not provide IDEA's global reference index. Conservatively retaining
 * visibility is correct when a local-use search cannot prove a member is unused.
 *
 * @param member declaration whose visibility may need lifting.
 * @param sourceClass source class (unused in the conservative standalone implementation).
 * @param membersToMove selected declarations.
 * @return `true` to retain accessible visibility.
 */
fun willBeUsedInSourceClass(
    @Suppress("UNUSED_PARAMETER") member: PsiElement,
    @Suppress("UNUSED_PARAMETER") sourceClass: org.jetbrains.kotlin.psi.KtClassOrObject,
    @Suppress("UNUSED_PARAMETER") membersToMove: Collection<KtNamedDeclaration>,
): Boolean = true

/** Removes a default argument from a moved parameter, as IDEA's pull-up algorithm requires. */
fun KtParameter.dropDefaultValue() {
    val from = equalsToken ?: return
    deleteChildRange(from, defaultValue ?: from)
}
