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
package com.intellij.ide.actions

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiQualifiedNamedElement

/**
 * Standalone replacement for IDEA's `QualifiedNameProviderUtil`.
 *
 * IDEA's version delegates to language-specific name providers. The J2K engine uses it only to
 * recognise calls to `java.util.stream.*` methods (`K2TypeArgumentsExpander`), so a PSI-based
 * qualified name is sufficient.
 */
object QualifiedNameProviderUtil {

    /**
     * Returns the qualified name of [element].
     *
     * @param element the element to name.
     * @return the qualified name for qualified named elements, `outer.name` for members,
     * the simple name for other named elements, or `null`.
     */
    @JvmStatic
    fun getQualifiedName(element: PsiElement): String? = when (element) {
        is PsiQualifiedNamedElement -> element.qualifiedName
        is PsiMember -> {
            val className = element.containingClass?.qualifiedName
            if (className != null) "$className.${element.name}" else element.name
        }
        is PsiNamedElement -> element.name
        else -> null
    }
}
