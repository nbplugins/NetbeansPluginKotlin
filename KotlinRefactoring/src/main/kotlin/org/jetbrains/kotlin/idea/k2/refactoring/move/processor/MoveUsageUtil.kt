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
package org.jetbrains.kotlin.idea.k2.refactoring.move.processor

import com.intellij.psi.PsiFile
import com.intellij.refactoring.util.MoveRenameUsageInfo

/**
 * Verbatim extracts of the two `MoveRenameUsageInfo` grouping helpers from IDEA's
 * `kotlin.refactorings.move.k2` `moveUsageUtil.kt`.
 *
 * The full `moveUsageUtil.kt` cannot be compiled standalone because it drags in the entire
 * move-descriptor machinery (`K2MoveTargetDescriptor`, `OuterInstanceReferenceUsageInfo`, …),
 * none of which the Copy Declaration retargeting path needs.  Only [groupByFile] and
 * [sortedByOffset] are referenced by `K2MoveRenameUsageInfo.retargetUsages`, so they are copied
 * here unchanged to keep the engine port self-contained.
 */

/**
 * Groups usages by their containing [PsiFile], producing a deterministic order by virtual-file
 * path so refactoring results are reproducible.
 */
internal fun <T : MoveRenameUsageInfo> List<T>.groupByFile(): Map<PsiFile, List<T>> = groupBy {
    it.element?.containingFile ?: error("Could not find containing file")
}.toSortedMap(object : Comparator<PsiFile> {
    // Use a sorted map to get consistent results by the refactoring
    // This is done to reduce flakiness and make the results reproducible
    override fun compare(o1: PsiFile?, o2: PsiFile?): Int {
        return o1?.virtualFile?.path?.compareTo(o2?.virtualFile?.path ?: return -1) ?: -1
    }
})

/**
 * Sorts the usages within each file by their element text offset.
 */
internal fun <T : MoveRenameUsageInfo> Map<PsiFile, List<T>>.sortedByOffset(): Map<PsiFile, List<T>> = mapValues { (_, value) ->
    value.sortedBy { it.element?.textOffset }
}
