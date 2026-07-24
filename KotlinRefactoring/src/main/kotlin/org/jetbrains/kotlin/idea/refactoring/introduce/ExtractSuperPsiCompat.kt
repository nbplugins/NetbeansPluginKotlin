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
package org.jetbrains.kotlin.idea.refactoring.introduce

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.siblings
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Inserts [declaration] before [targetSibling], preserving IDEA's whitespace/anchor behavior.
 *
 * This is the verbatim PSI-only part of IDEA's `introduceUtils.kt`; its surrounding editor-driven
 * selection utilities are intentionally absent from the standalone refactoring module.
 *
 * @param declaration declaration to insert.
 * @param targetSibling declaration sibling that anchors insertion.
 * @return inserted declaration.
 */
fun <T : KtDeclaration> insertDeclaration(declaration: T, targetSibling: PsiElement): T {
    val targetParent = targetSibling.parent
    val anchors = buildList {
        add(targetSibling)
        if (targetSibling is KtEnumEntry) add(targetSibling.siblings().last { it is KtEnumEntry })
    }
    val anchor = anchors.minBy { it.startOffset }.parentsWithSelf.first { it.parent == targetParent }
    val targetContainer = anchor.parent!!
    @Suppress("UNCHECKED_CAST")
    return (targetContainer.addBefore(declaration, anchor) as T).apply {
        targetContainer.addBefore(KtPsiFactory(declaration.project).createWhiteSpace("\n\n"), anchor)
    }
}
