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

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

/**
 * Applies the K2 Move File handler's mutation with a NetBeans-selected package.
 *
 * The upstream handler derives the destination package from IntelliJ's source-root-aware
 * `PsiDirectory`. Standalone K2 PSI is backed by `LightVirtualFile`s and cannot expose that model,
 * so F4.1 supplies the package chosen through NetBeans' [KotlinPackageTarget] equivalent directly.
 * The actual rewrite, declaration mapping, and eligibility marker remain in [K2MoveFilesHandler].
 *
 * @param file writable session file whose package may be rewritten
 * @param targetPackage selected destination package
 * @param rewritePackage whether the source package matched its source-root-relative directory
 * @param oldToNewMap mutable moved declaration map used for later K2 retargeting
 */
fun K2MoveFilesHandler.prepareMovedFileForPackage(
    file: KtFile,
    targetPackage: FqName,
    rewritePackage: Boolean,
    oldToNewMap: MutableMap<PsiElement, PsiElement>,
) {
    // This is the mapping half of K2MoveFilesHandler.prepareMovedFile with the one IDEA-only
    // dependency replaced: the handler normally derives the package from JavaDirectoryService.
    // A mismatch must preserve its declaration exactly, matching K2MoveFilesHandler.needsUpdate.
    if (rewritePackage && file.packageFqName != targetPackage) file.updatePackageDirective(targetPackage)
    oldToNewMap[file] = file
    file.allDeclarationsToUpdate.forEach { declaration -> oldToNewMap[declaration] = declaration }
}
