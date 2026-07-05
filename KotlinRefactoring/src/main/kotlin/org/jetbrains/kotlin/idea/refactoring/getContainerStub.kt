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
package org.jetbrains.kotlin.idea.refactoring

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.util.ConflictsUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor

/**
 * Verbatim port of the one function Move Declaration needs from IDEA's
 * `kotlin.refactorings.common/kotlinCommonRefactoringUtil.kt` — that file as a whole is too
 * IDE-heavy to port (`ConflictsDialog`, `RootKindFilter`; see the pom.xml comment on
 * `copy-kotlin-refactoring-common-inline`), so only this PSI-only lookup is copied here.
 */
fun PsiElement.getContainer(): PsiElement {
    return when (this) {
        is KtElement -> PsiTreeUtil.getParentOfType(
            this,
            KtPropertyAccessor::class.java,
            org.jetbrains.kotlin.psi.KtParameter::class.java,
            KtProperty::class.java,
            KtNamedFunction::class.java,
            KtConstructor::class.java,
            KtClassOrObject::class.java
        ) ?: containingFile
        else -> ConflictsUtil.getContainer(this)
    }
}
