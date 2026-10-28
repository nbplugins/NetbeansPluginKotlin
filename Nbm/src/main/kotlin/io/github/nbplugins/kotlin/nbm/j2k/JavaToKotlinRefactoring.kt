/*
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
 */
package io.github.nbplugins.kotlin.nbm.j2k

import org.netbeans.api.project.Project
import org.netbeans.modules.refactoring.api.AbstractRefactoring
import org.openide.filesystems.FileObject
import org.openide.util.lookup.Lookups

/**
 * Carries the Java source file selected for conversion to Kotlin.
 *
 * The refactoring source is the Java [FileObject] rather than an editor document: the conversion
 * starts from a file node or from the Java editor, and its applicability must not depend on which
 * of the two places invoked it. [KotlinRefactoringsFactory][org.jetbrains.kotlin.refactorings
 * .rename.KotlinRefactoringsFactory] therefore recognizes this carrier before its
 * "editor document with a `.kt` file" guard.
 *
 * The conversion itself is performed by [JavaToKotlinPlugin]; this class only transports the
 * parameters and the failure message that the plugin has to report back to the caller.
 *
 * @param project the NetBeans project that owns [javaFile]
 * @param javaFile the Java source file to convert
 */
class JavaToKotlinRefactoring(
    val project: Project,
    val javaFile: FileObject,
) : AbstractRefactoring(Lookups.fixed(javaFile)) {

    /**
     * User-facing message of the last failed [JavaToKotlinElement.performChange] call.
     *
     * `performChange` cannot report failure through the refactoring API: the session registers its
     * undo entry even when an element throws. The element therefore records the reason here, and
     * [Java2KotlinConverter] shows it after the session completes.
     */
    var failureMessage: String? = null
}
