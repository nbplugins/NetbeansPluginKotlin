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
package org.jetbrains.kotlin.idea.k2.refactoring.move.processor

import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.idea.k2.refactoring.move.processor.usages.findMoveDeclarationUsages
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

/**
 * Finds Kotlin usages affected by moving a complete Kotlin file in a standalone K2 session.
 *
 * This is the portable semantic half of upstream `KtFile.findUsages`. IDEA obtains external
 * references through an indexed Java/light-class path; NetBeans instead uses the registered
 * [org.jetbrains.kotlin.idea.k2.refactoring.move.KotlinMoveUsageSearchService]. Non-code searches
 * remain disabled for F4.1, consistent with the unchecked IDEA defaults and existing move ports.
 *
 * @param searchInCommentsAndStrings whether comments and string usages were requested
 * @param searchForText whether non-code text usages were requested
 * @param targetPackage package that declarations will belong to after the physical move
 * @return Kotlin usages that require package/reference retargeting
 */
fun KtFile.findMoveFileUsages(
    searchInCommentsAndStrings: Boolean,
    searchForText: Boolean,
    targetPackage: FqName,
): List<UsageInfo> {
    // Parameters are deliberately retained to mirror IDEA's API. F4.1 does not provide the
    // platform text-occurrence index, so only semantic Kotlin references are searched.
    @Suppress("UNUSED_VARIABLE")
    val ignoredNonCodeSearch = searchInCommentsAndStrings || searchForText || targetPackage.isRoot
    return topLevelDeclarationsToUpdate.flatMap(::findMoveDeclarationUsages)
}
