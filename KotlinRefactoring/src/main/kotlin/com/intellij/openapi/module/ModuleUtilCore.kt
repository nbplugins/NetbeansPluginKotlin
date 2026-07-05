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
package com.intellij.openapi.module

import com.intellij.psi.PsiElement

/**
 * Stub of IntelliJ's `com.intellij.openapi.module.ModuleUtilCore`. Every [PsiElement] in a
 * NetBeans project belongs to the same [SingletonModule] — see its doc comment for why this is the
 * correct model, not a simplification, for this plugin's single-module architecture.
 */
object ModuleUtilCore {
    @JvmStatic
    fun findModuleForPsiElement(element: PsiElement): Module? =
        if (element.isValid) SingletonModule.forProject(element.project) else null
}
