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
package org.jetbrains.kotlin.idea.refactoring

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KtPsiClassWrapper
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Returns whether this declaration represents an abstract Kotlin member.
 *
 * @return `true` for explicit abstract declarations and bodyless interface callables.
 */
fun KtDeclaration.isAbstract(): Boolean = when {
    hasModifier(KtTokens.ABSTRACT_KEYWORD) -> true
    containingClassOrObject?.isInterfaceClass() != true -> false
    this is KtProperty -> initializer == null && delegate == null && accessors.isEmpty()
    this is KtNamedFunction -> !hasBody()
    else -> false
}

/**
 * Returns whether [this] is a Kotlin or Java interface class representation.
 *
 * @return `true` when the PSI element is an interface.
 */
fun PsiElement.isInterfaceClass(): Boolean = when (this) {
    is KtClass -> isInterface()
    is PsiClass -> isInterface
    is KtPsiClassWrapper -> psiClass.isInterface
    else -> false
}

/**
 * Returns whether this declaration is a member of [klass]'s companion object.
 *
 * @param klass source class/object.
 * @return `true` when this member belongs to that companion object.
 */
fun KtNamedDeclaration.isCompanionMemberOf(klass: org.jetbrains.kotlin.psi.KtClassOrObject): Boolean {
    val containingObject = containingClassOrObject as? KtObjectDeclaration ?: return false
    return containingObject.isCompanion() && containingObject.containingClassOrObject == klass
}

/** Removes a Kotlin `override` modifier when a moved declaration no longer overrides a member. */
fun PsiElement.removeOverrideModifier() {
    when (this) {
        is KtNamedFunction, is KtProperty -> modifierList?.getModifier(KtTokens.OVERRIDE_KEYWORD)?.delete()
    }
}
