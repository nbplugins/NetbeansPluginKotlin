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
package com.intellij.refactoring.move

import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewDescriptor

/**
 * Stub of IntelliJ's `com.intellij.refactoring.move.moveClassesOrPackages.MoveMultipleElementsViewDescriptor`
 * (not present in the checked-out Community sources). Only used by
 * [K2MoveOperationDescriptor.usageViewDescriptor][org.jetbrains.kotlin.idea.k2.refactoring.move.processor.usageViewDescriptor]
 * to satisfy `createUsageViewDescriptor`; the NetBeans Move Declaration flow never renders IDEA's
 * usage-view preview UI, so this only needs to hold the data.
 */
class MoveMultipleElementsViewDescriptor(
    private val elementsToMove: Array<PsiElement>,
    private val targetName: String
) : UsageViewDescriptor {
    override fun getElements(): Array<PsiElement> = elementsToMove
    override fun getProcessedElementsHeader(): String = "Elements to be moved to $targetName"
    override fun getCodeReferencesText(usagesCount: Int, filesCount: Int): String =
        "References to be changed ($usagesCount usages in $filesCount files)"
}
