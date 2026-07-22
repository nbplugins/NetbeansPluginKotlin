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
package org.jetbrains.kotlin.idea.refactoring.introduce.extractClass

import com.intellij.psi.PsiElement
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Standalone counterpart of IDEA's Extract Interface action handler.
 *
 * NetBeans owns action invocation and dialogs, while the copied IDEA K2 engine uses this narrow
 * contract solely to validate source classes and name its write command.
 */
object KotlinExtractInterfaceHandler {
    /** The user-visible command name used by the copied engine. */
    const val REFACTORING_NAME: String = "Extract Interface"

    /**
     * Returns the reason an interface cannot be extracted from [klass], or `null` when valid.
     *
     * @param klass source Kotlin declaration.
     * @return validation message or `null`.
     */
    @JvmStatic
    fun getErrorMessage(klass: KtClassOrObject): String? =
        if (klass is KtClass && klass.isAnnotation()) "An interface cannot be extracted from an annotation class." else null
}

/**
 * Standalone counterpart of IDEA's Extract Superclass action handler.
 *
 * The actual NetBeans action/UI is separate; the copied engine only needs this validation and
 * command-name contract.
 */
object KotlinExtractSuperclassHandler {
    /** The user-visible command name used by the copied engine. */
    const val REFACTORING_NAME: String = "Extract Superclass"

    /**
     * Returns the reason a superclass cannot be extracted from [klass], or `null` when valid.
     *
     * @param klass source Kotlin declaration.
     * @return validation message or `null`.
     */
    @JvmStatic
    fun getErrorMessage(@Suppress("UNUSED_PARAMETER") klass: KtClassOrObject): String? = null
}

/**
 * Contract used by the copied K2 conflict searcher.
 *
 * NetBeans presents collected conflicts itself, so this keeps IDEA's analysis contract free from
 * IDEA dialog dependencies.
 */
interface KotlinExtractSuperConflictSearcher {
    /**
     * Collects conflicts for an extraction request.
     *
     * @param originalClass source class/object.
     * @param memberInfos selected members.
     * @param targetParent target PSI parent/directory.
     * @param newClassName requested extracted type name.
     * @param isExtractInterface whether the target is an interface.
     * @return conflict messages grouped by PSI element.
     */
    fun collectConflicts(
        originalClass: KtClassOrObject,
        memberInfos: List<org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfo>,
        targetParent: PsiElement,
        newClassName: String,
        isExtractInterface: Boolean,
    ): MultiMap<PsiElement, String>
}
