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
package com.intellij.refactoring.rename

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.usageView.UsageInfo

/**
 * Standalone replacement for IDEA's `RenamePsiElementProcessor`.
 *
 * The real processor is part of the refactoring engine and finds usages through the global
 * reference index. The J2K engine uses it in `K2ConvertGettersAndSettersToPropertyProcessing` to
 * rename a constructor parameter (including a `field`-named one) after converting a getter/setter
 * pair into a property.
 *
 * The replacement searches the **declaring file only** and performs the rename through
 * [PsiReference.handleElementRename], which every Kotlin and Java reference implements. References
 * in other files are therefore not renamed — a documented limitation; the property conversion in
 * NetBeans operates on a single file.
 */
object RenamePsiElementProcessor {

    /**
     * Returns the processor responsible for [element].
     *
     * @param element the element to rename.
     * @return this singleton, which handles every element type.
     */
    @JvmStatic
    fun forElement(element: PsiElement): RenamePsiElementProcessor = this

    /**
     * Collects the usages of [element] inside its declaring file.
     *
     * @param element the element being renamed.
     * @param searchScope the scope to search; usages outside it are skipped.
     * @param searchInCommentsAndStrings ignored — the J2K engine always passes `false`.
     * @return one [UsageInfo] per reference resolving to [element].
     */
    fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean
    ): Collection<UsageInfo> {
        val file = element.containingFile ?: return emptyList()
        val virtualFile = file.virtualFile
        if (virtualFile != null && !searchScope.contains(virtualFile)) return emptyList()
        val result = ArrayList<UsageInfo>()
        PsiTreeUtil.processElements(file, PsiElementProcessor { candidate ->
            for (reference in candidate.references) {
                if (reference.isReferenceTo(element)) {
                    result.add(UsageInfo(reference))
                }
            }
            true
        })
        return result
    }

    /**
     * Creates the usage entry that will be renamed.
     *
     * @param element the element being renamed.
     * @param usage the usage found by [findReferences].
     * @param usageElement the PSI element the usage points at.
     * @return [usage] unchanged, so that its reference is preserved for the rename.
     */
    fun createUsageInfo(element: PsiElement, usage: UsageInfo, usageElement: PsiElement?): UsageInfo =
        usage

    /**
     * Renames [element] and every usage to [newName].
     *
     * @param element the declaration to rename.
     * @param newName the new name.
     * @param usages the usages collected by [findReferences].
     * @param listener notified after the declaration has been renamed.
     */
    fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<UsageInfo>,
        listener: RefactoringElementListener?
    ) {
        ApplicationManager.getApplication().runWriteAction(Computable {
            for (usage in usages) {
                val reference: PsiReference = usage.reference ?: continue
                // A broken reference must not abort the whole conversion.
                runCatching { reference.handleElementRename(newName) }
            }
            (element as? PsiNamedElement)?.setName(newName)
            listener?.elementRenamed(element)
            Unit
        })
    }
}
