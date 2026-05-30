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
package org.jetbrains.kotlin.idea.codeinsight.utils

import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*

/**
 * Converts a [Visibility] descriptor to the corresponding modifier keyword token.
 *
 * @return the [KtModifierKeywordToken] for this visibility
 * @throws IllegalStateException if the visibility cannot be mapped to a keyword
 */
fun Visibility.toKeywordToken(): KtModifierKeywordToken = when (val normalized = normalize()) {
    Visibilities.Public -> KtTokens.PUBLIC_KEYWORD
    Visibilities.Protected -> KtTokens.PROTECTED_KEYWORD
    Visibilities.Internal -> KtTokens.INTERNAL_KEYWORD
    else -> if (Visibilities.isPrivate(normalized)) KtTokens.PRIVATE_KEYWORD
            else error("Unexpected visibility '$normalized'")
}

/**
 * Converts a visibility [KtModifierKeywordToken] to its [Visibility] descriptor.
 *
 * @return the [Visibility] for this keyword token
 * @throws IllegalArgumentException if the token is not a visibility keyword
 */
fun KtModifierKeywordToken.toVisibility(): Visibility = when (this) {
    KtTokens.PUBLIC_KEYWORD -> Visibilities.Public
    KtTokens.PRIVATE_KEYWORD -> Visibilities.Private
    KtTokens.PROTECTED_KEYWORD -> Visibilities.Protected
    KtTokens.INTERNAL_KEYWORD -> Visibilities.Internal
    else -> throw IllegalArgumentException("Unknown visibility modifier '$this'")
}


/**
 * Returns true if this declaration can be made `public`.
 *
 * False only for constructors of sealed classes, which are always `protected` or `private`.
 */
fun KtModifierListOwner.canBePublic(): Boolean = !isSealedClassConstructor()

/**
 * Returns true if this declaration can be made `private`.
 *
 * False for abstract declarations, `@JvmField` properties, `actual`/`expect` declarations,
 * primary constructors of annotation classes, members of annotation classes, and abstract
 * interface members (those without a body).
 */
fun KtModifierListOwner.canBePrivate(): Boolean {
    if (modifierList?.hasModifier(KtTokens.ABSTRACT_KEYWORD) == true) return false
    if (isAnnotationClassPrimaryConstructor()) return false
    if (this is KtProperty && annotationEntries.any { it.typeReference?.text?.endsWith("JvmField") == true }) return false

    if (this is KtDeclaration) {
        if (hasModifier(KtTokens.ACTUAL_KEYWORD) || hasModifier(KtTokens.EXPECT_KEYWORD)) return false
        val containingClass = containingClassOrObject as? KtClass ?: return true
        if (containingClass.isAnnotation()) return false
        // Abstract interface members (no body) cannot be private
        if (containingClass.isInterface() && (this as? KtDeclarationWithBody)?.hasBody() != true) return false
    }

    return true
}

/**
 * Returns true if this declaration can be made `protected`.
 *
 * Requires the declaration to be a member of a non-interface class (or a primary constructor
 * parameter in a primary constructor). Not applicable to constructors of final or annotation classes.
 */
fun KtModifierListOwner.canBeProtected(): Boolean {
    return when (val parent = if (this is KtPropertyAccessor) this.property.parent else this.parent) {
        is KtClassBody -> {
            val parentClass = parent.parent as? KtClass
            parentClass != null && !parentClass.isInterface() && !isFinalClassConstructor()
        }
        is KtParameterList -> parent.parent is KtPrimaryConstructor
        is KtClass -> !isAnnotationClassPrimaryConstructor() && !isFinalClassConstructor()
        else -> false
    }
}

/**
 * Returns true if this declaration can be made `internal`.
 *
 * False for primary constructors of annotation classes, constructors of sealed classes, and
 * `@JvmField` companion object members in interfaces.
 */
fun KtModifierListOwner.canBeInternal(): Boolean {
    if (containingClass()?.isInterface() == true) {
        val objectDeclaration = getStrictParentOfType<KtObjectDeclaration>() ?: return false
        if (objectDeclaration.isCompanion() &&
            (this as? KtProperty)?.annotationEntries?.any { it.typeReference?.text?.endsWith("JvmField") == true } == true) return false
    }
    return !isAnnotationClassPrimaryConstructor() && !isSealedClassConstructor()
}

private fun KtModifierListOwner.isAnnotationClassPrimaryConstructor(): Boolean =
    this is KtPrimaryConstructor && (this.parent as? KtClass)?.hasModifier(KtTokens.ANNOTATION_KEYWORD) ?: false

private fun KtModifierListOwner.isFinalClassConstructor(): Boolean {
    if (this !is KtConstructor<*>) return false
    val ktClass = getContainingClassOrObject() as? KtClass ?: return false
    return ktClass.modalityModifierType()?.equals(KtTokens.FINAL_KEYWORD) ?: true
}

private fun KtModifierListOwner.isSealedClassConstructor(): Boolean {
    if (this !is KtConstructor<*>) return false
    val ktClass = getContainingClassOrObject() as? KtClass ?: return false
    return ktClass.isSealed()
}
