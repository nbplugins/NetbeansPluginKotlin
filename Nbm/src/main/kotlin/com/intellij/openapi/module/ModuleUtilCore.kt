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
 * Runtime-precedence twin of `KotlinRefactoring`'s `com.intellij.openapi.module.ModuleUtilCore` —
 * see [SingletonModule]'s doc comment for why this duplicate exists. Without it, a bundled
 * platform JAR's real `ModuleUtilCore.findModuleForPsiElement()` wins classloading in the packaged
 * `.nbm` (it does not in unit tests, where the classpath differs) and throws
 * `IllegalStateException: ProjectFileIndex.getInstance must not return null`, since this standalone
 * environment never registers a `ProjectFileIndex` service.
 */
object ModuleUtilCore {
    @JvmStatic
    fun findModuleForPsiElement(element: PsiElement): Module? =
        if (element.isValid) SingletonModule.forProject(element.project) else null
}
