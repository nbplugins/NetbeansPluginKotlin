/*******************************************************************************
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
package com.intellij.refactoring.util

import com.intellij.lang.findUsages.DescriptiveNameUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

/**
 * Stub of IntelliJ's `com.intellij.refactoring.util.RefactoringUIUtil` (not present in our
 * checked-out Community sources). Only [getDescription] is needed — it builds the "kind 'name'"
 * phrase used throughout Move Declaration's conflict messages. Real (not faked): uses the actual
 * declaration kind and [DescriptiveNameUtil] (already ported for E9.3), just without IDEA's
 * `LangBundle`-driven pluralization for the [includeParent] case.
 */
object RefactoringUIUtil {
    @JvmStatic
    fun getDescription(element: PsiElement?, includeParent: Boolean): String {
        if (element == null) return ""
        val kind = when (element) {
            is KtClass -> if (element.isInterface()) "interface" else "class"
            is KtObjectDeclaration -> if (element.isCompanion()) "companion object" else "object"
            is KtTypeAlias -> "type alias"
            is KtConstructor<*> -> "constructor"
            is KtFunction -> "function"
            is KtProperty -> "property"
            is PsiFile -> "file"
            else -> "declaration"
        }
        val name = DescriptiveNameUtil.getDescriptiveName(element)
        val self = "$kind '$name'"
        if (!includeParent) return self
        val parentFile = element.containingFile ?: return self
        return "$self in ${parentFile.name}"
    }
}
