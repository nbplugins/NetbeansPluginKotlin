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
import org.jetbrains.kotlin.psi.psiUtil.isAncestor

/**
 * Verbatim port of the one function Move/Copy Declaration need from IDEA's
 * `kotlin.refactorings.common/pullUp/commonPullUpConflictsUtils.kt`. The rest of that file
 * (`checkPullUpConflicts`, `willBeUsedInSourceClass`) is Pull Members Up-specific and IDE-UI-heavy
 * (`ConflictsDialog`, `runProcessWithProgressSynchronously`); this plugin does not yet implement
 * Pull Members Up, so only this one PSI-only predicate is needed.
 */
fun PsiElement?.willBeMoved(declarationsToMove: Iterable<KtNamedDeclaration>): Boolean {
    return this != null && declarationsToMove.any { it.isAncestor(this, false) }
}
