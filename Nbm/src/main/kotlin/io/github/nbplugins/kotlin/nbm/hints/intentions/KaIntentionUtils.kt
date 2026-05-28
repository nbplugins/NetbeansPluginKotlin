/*******************************************************************************
 * Copyright 2000-2024 JetBrains s.r.o.
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
package io.github.nbplugins.kotlin.nbm.hints.intentions

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Returns the PSI anchor for inserting / removing a type annotation on [element].
 *
 * This mirrors [org.jetbrains.kotlin.hints.intentions.getAnchor] for use in the K2 path.
 *
 * @param element the callable declaration whose type annotation position is requested
 * @return the anchor element, or null if the declaration kind is unsupported
 */
fun getAnchorK2(element: KtCallableDeclaration): PsiElement? = when (element) {
    is KtProperty, is KtParameter -> element.nameIdentifier
    is KtNamedFunction -> element.valueParameterList
    else -> null
}
