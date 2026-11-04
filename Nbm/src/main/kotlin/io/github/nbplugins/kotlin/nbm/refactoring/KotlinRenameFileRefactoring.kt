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
 * Carries one Kotlin-file rename through the NetBeans refactoring lifecycle.
 *
 * This carrier deliberately differs from [org.netbeans.modules.refactoring.api.RenameRefactoring]:
 * the ordinary Rename refactoring remains responsible for caret-based declaration renames. When
 * [updatePrimaryClass] is `true`, generic Rename also renames a top-level Kotlin class whose name
 * matches the original file basename, together with its supported Kotlin references.
 *
 * @param file physical Kotlin source file to rename
 * @param newName target basename without the `.kt` extension
 * @param updatePrimaryClass whether a matching top-level Kotlin class and its references follow
 *   this physical file rename
 */
class KotlinRenameFileRefactoring(
    val file: FileObject,
    var newName: String,
    val updatePrimaryClass: Boolean = false,
) : AbstractRefactoring(Lookups.fixed(file))
