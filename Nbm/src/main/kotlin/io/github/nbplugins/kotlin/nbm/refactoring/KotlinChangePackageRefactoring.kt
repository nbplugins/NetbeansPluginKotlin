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
import org.openide.util.lookup.Lookups

/**
 * Carries selected Kotlin sources and requested package-change parameters through NetBeans.
 *
 * @param selection physical Kotlin sources whose package directives will change without relocation
 */
class KotlinChangePackageRefactoring(
    val selection: KotlinChangePackageSourceSelection,
) : AbstractRefactoring(Lookups.fixed(*selection.sourceFiles.toTypedArray())) {
    /** User-selected base package, empty for Kotlin's root package. */
    var targetPackage: String = ""

    /** Whether supported external Kotlin references and direct imports should be retargeted. */
    var updateReferences: Boolean = true
}
