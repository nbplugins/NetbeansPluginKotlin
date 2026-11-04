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
package io.github.nbplugins.kotlin.nbm.refactoring

import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.filesystems.FileObject
import org.openide.util.lookup.Lookups

/**
 * Carries one Kotlin package-directory rename through the NetBeans refactoring lifecycle.
 *
 * Every descendant is always renamed physically. When [updatePackage] is `true` (generic Rename),
 * every Kotlin descendant also receives the package derived from its post-rename path and supported
 * Kotlin references are updated. When it is `false` (explicit "Rename Kotlin Package Directory..."),
 * package directives and code references stay unchanged; use Change Kotlin Package for those.
 *
 * @param directory selected physical directory containing Kotlin descendants
 * @param newName target simple directory name
 * @param selection source files verified while the package-tree action was enabled, when available
 * @param updatePackage whether Kotlin package directives and supported references follow the rename
 */
class KotlinRenameDirectoryRefactoring(
    val directory: FileObject,
    var newName: String,
    internal val selection: KotlinMoveSourceSelection? = null,
    val updatePackage: Boolean = false,
) : AbstractRefactoring(Lookups.fixed(directory))
